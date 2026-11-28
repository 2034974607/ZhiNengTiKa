package app.zhinengtika.nativeapp.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OfflineStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * An encrypted metadata index points to immutable, individually encrypted card files. A transaction
 * publishes its index only after every new card file is synced. Replacing/deleting an entry commits
 * the new index before collecting obsolete files, so interrupted writes leave the previous library
 * readable. The root belongs exclusively to this engine; instances in one process share a lock.
 *
 * All methods perform file IO and must run off the Android main thread. summaries() and directory()
 * read only metadata. cards() remains a compatibility API and intentionally loads all card bodies.
 */
class FileOfflineTemplateStore(
    rootDirectory: File,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
    private val legacySource: LegacyOfflineLibrarySource? = null,
    private val limits: OfflineLibraryLimits = OfflineLibraryLimits(),
    private val atomicWrite: (File, ByteArray) -> Unit = OfflineLibraryFileIo::writeAtomically,
) {
    private val root = rootDirectory.canonicalFile
    private val cardDirectory = File(root, "cards")
    private val indexFile = File(root, "index.enc")
    private val lock = LOCKS.getOrPut(root.path) { Any() }
    private val json = Json { ignoreUnknownKeys = true }
    private var recovered = false
    private var legacyCleared = legacySource == null

    fun summaries(): List<OfflineCardSummary> = access("读取离线题卡目录") {
        ready().cards.map { it.summary }
    }

    fun card(id: String): OfflineTemplateEntry? = access("读取离线题卡") {
        ready().cards.firstOrNull { it.summary.id == id }?.let(::readCard)
    }

    fun cards(): List<OfflineTemplateEntry> = access("读取全部离线题卡") {
        ready().cards.map(::readCard)
    }

    fun directory(): List<DirectoryEntry> = access("读取离线目录") { ready().directory.toList() }

    fun saveCard(entry: OfflineTemplateEntry) = saveCards(sequenceOf(entry))

    /** Streams bodies to disk and publishes one index; an exception leaves the whole batch uncommitted. */
    fun saveCards(entries: Sequence<OfflineTemplateEntry>): Unit = access("保存离线题卡") {
        val before = ready()
        transact(before) { transaction -> entries.forEach(transaction::saveCard) }
        Unit
    }

    fun saveDirectory(entries: List<DirectoryEntry>): Unit = access("保存离线目录") {
        val before = ready()
        transact(before) { transaction -> entries.forEach(transaction::saveDirectory) }
        Unit
    }

    fun deleteCard(id: String) = access("删除离线题卡") {
        val before = ready()
        if (before.cards.any { it.summary.id == id }) {
            commit(before.copy(cards = before.cards.filterNot { it.summary.id == id }))
            collectUnused(readIndex())
        }
    }

    fun deleteDirectory(id: String) = access("删除离线目录") {
        val before = ready()
        if (before.directory.any { it.id == id }) {
            commit(before.copy(directory = before.directory.filterNot { it.id == id }))
        }
    }

    /** The persisted migration marker prevents old preferences from resurrecting a cleared library. */
    fun clearAll() = access("清空离线资料库") {
        ensureDirectories()
        // Do not decode possibly damaged legacy data just to satisfy an explicit clear operation.
        commit(OfflineLibraryIndex(legacyMigrationComplete = true))
        recovered = false
        clearLegacy()
        collectUnused(OfflineLibraryIndex(legacyMigrationComplete = true))
        recovered = true
    }

    /** Advisory legacy API; saveCard performs the authoritative check with its code and old size. */
    fun canAcceptCard(incomingBytes: Int): Boolean = access("检查离线资料库容量") {
        val index = ready()
        incomingBytes in 0..limits.maxCardBytes && index.cards.size < limits.maxCards &&
            index.cards.sumOf { it.cardBytes } + incomingBytes <= limits.maxTotalBytes
    }

    /** Advisory legacy API; saveDirectory deduplicates before enforcing the real limit. */
    fun canAcceptDirectory(incomingCount: Int): Boolean = access("检查离线目录容量") {
        incomingCount >= 0 && ready().directory.size.toLong() + incomingCount <= limits.maxDirectoryEntries
    }

    private fun ready(): OfflineLibraryIndex {
        ensureDirectories()
        var index = readIndex()
        if (!index.legacyMigrationComplete) {
            // Decode the complete old snapshot before any new files or index are committed.
            val legacy = legacySource?.read()
            index = transact(index, migrationComplete = true) { transaction ->
                legacy?.cards?.forEach(transaction::saveCard)
                legacy?.directory?.forEach(transaction::saveDirectory)
            }
        }
        clearLegacy()
        if (!recovered) {
            collectUnused(index)
            recovered = true
        }
        return index
    }

    private fun clearLegacy() {
        if (!legacyCleared) {
            legacySource!!.clear()
            legacyCleared = true
        }
    }

    private fun transact(
        before: OfflineLibraryIndex,
        migrationComplete: Boolean = before.legacyMigrationComplete,
        operation: (Transaction) -> Unit,
    ): OfflineLibraryIndex {
        val transaction = Transaction(before)
        var published = false
        try {
            operation(transaction)
            val after = transaction.index(migrationComplete)
            commit(after)
            published = true
            // No card body is rewritten for an unrelated import or for a directory-only change.
            val committedFiles = after.cards.mapTo(hashSetOf()) { it.fileName }
            transaction.createdFiles.filterNot { it.name in committedFiles }.forEach(::deleteOwnedCardFile)
            before.cards.filterNot { it.fileName in committedFiles }
                .forEach { deleteOwnedCardFile(File(cardDirectory, it.fileName)) }
            return after
        } catch (error: Throwable) {
            if (!published) {
                for (file in transaction.createdFiles) {
                    try {
                        deleteOwnedCardFile(file)
                    } catch (cleanupError: Exception) {
                        error.addSuppressed(cleanupError)
                    }
                }
            } else {
                recovered = false
            }
            throw error
        }
    }

    private inner class Transaction(before: OfflineLibraryIndex) {
        private val cards = before.cards.associateByTo(linkedMapOf()) { it.summary.code }
        private val directory = before.directory.associateByTo(linkedMapOf()) { it.code }
        val createdFiles = mutableListOf<File>()
        private var totalBytes = before.cards.sumOf { it.cardBytes }

        fun saveCard(incoming: OfflineTemplateEntry) {
            validateCode(incoming.card.code)
            validateId(incoming.id)
            val code = incoming.card.code
            val previous = cards[code]
            val id = previous?.summary?.id ?: incoming.id
            storageCheck(cards.values.none { it.summary.id == id && it.summary.code != code }) {
                "题卡标识与另一张题卡冲突"
            }
            val subject = OfflineSubjects.resolve(
                code = code, title = incoming.card.title, sourcePath = incoming.sourceLabel,
                explicitSubject = incoming.subject,
                knownSubject = previous?.summary?.subject ?: directory[code]?.subject,
            )
            val entry = incoming.copy(id = id, subject = subject)
            validateMetadata(entry.card.title, entry.sourceLabel, entry.warnings)
            val cardBytes = json.encodeToString(TemplateCard.serializer(), entry.card).toByteArray(Charsets.UTF_8).size
            storageCheck(cardBytes <= limits.maxCardBytes) { "单张题卡不能超过 8 MiB" }
            storageCheck(cards.size + (if (previous == null) 1 else 0) <= limits.maxCards) {
                "离线题卡数量已达上限（${limits.maxCards} 张）"
            }
            val nextTotal = totalBytes - (previous?.cardBytes ?: 0L) + cardBytes
            storageCheck(nextTotal <= limits.maxTotalBytes) { "离线资料库总容量已达上限" }
            val plain = json.encodeToString(OfflineTemplateEntry.serializer(), entry)
            val file = File(cardDirectory, "card-${UUID.randomUUID().toString().replace("-", "")}.enc")
            // Track the candidate before writing so a writer that fails after creating it is cleaned up.
            createdFiles += file
            atomicWrite(file, encryptedBytes(plain, MAX_ENCRYPTED_CARD_BYTES))
            cards[code] = StoredOfflineCard(
                summary = OfflineCardSummary(id, code, entry.card.title, subject, entry.sourceLabel,
                    entry.isDemo, entry.importedAtMillis, entry.warnings.toList()),
                fileName = file.name, cardBytes = cardBytes.toLong(), plainSha256 = sha256(plain),
            )
            totalBytes = nextTotal
            val oldDirectory = directory[code]
            saveDirectory(DirectoryEntry(
                id = oldDirectory?.id ?: UUID.randomUUID().toString(), code = code,
                title = entry.card.title, subject = subject, sourceLabel = entry.sourceLabel,
            ))
        }

        fun saveDirectory(incoming: DirectoryEntry) {
            validateCode(incoming.code)
            validateId(incoming.id)
            validateMetadata(incoming.title, incoming.sourceLabel, emptyList())
            val previous = directory[incoming.code]
            val id = previous?.id ?: incoming.id
            storageCheck(directory.values.none { it.id == id && it.code != incoming.code }) {
                "目录标识与另一条目录冲突"
            }
            storageCheck(directory.size + (if (previous == null) 1 else 0) <= limits.maxDirectoryEntries) {
                "离线目录条目数已达上限（${limits.maxDirectoryEntries} 条）"
            }
            directory[incoming.code] = incoming.copy(id = id, subject = OfflineSubjects.resolve(
                code = incoming.code, title = incoming.title, sourcePath = incoming.sourceLabel,
                explicitSubject = incoming.subject,
                knownSubject = previous?.subject ?: cards[incoming.code]?.summary?.subject,
            ))
            // A corrected directory category also repairs metadata without reading/re-encrypting a body.
            cards[incoming.code]?.let { stored ->
                cards[incoming.code] = stored.copy(summary = stored.summary.copy(
                    subject = OfflineSubjects.resolve(
                        code = incoming.code, title = stored.summary.title,
                        sourcePath = incoming.sourceLabel, explicitSubject = directory[incoming.code]!!.subject,
                        knownSubject = stored.summary.subject,
                    ),
                ))
            }
        }

        fun index(migrationComplete: Boolean) = OfflineLibraryIndex(
            cards = cards.values.toList(), directory = directory.values.toList(),
            legacyMigrationComplete = migrationComplete,
        )
    }

    private fun readCard(stored: StoredOfflineCard): OfflineTemplateEntry {
        val plain = decrypt(readBounded(File(cardDirectory, stored.fileName), MAX_ENCRYPTED_CARD_BYTES))
        storageCheck(sha256(plain) == stored.plainSha256) { "离线题卡文件校验失败：${stored.summary.code}" }
        val entry = json.decodeFromString(OfflineTemplateEntry.serializer(), plain)
        storageCheck(entry.id == stored.summary.id && entry.card.code == stored.summary.code) {
            "离线题卡文件与目录不匹配：${stored.summary.code}"
        }
        return entry.copy(subject = stored.summary.subject)
    }

    private fun readIndex(): OfflineLibraryIndex {
        if (!indexFile.exists()) return OfflineLibraryIndex()
        val decoded = json.decodeFromString(OfflineLibraryIndex.serializer(),
            decrypt(readBounded(indexFile, MAX_ENCRYPTED_INDEX_BYTES)))
        storageCheck(decoded.version == 1) { "不支持的离线资料库索引版本" }
        storageCheck(decoded.cards.size <= limits.maxCards && decoded.directory.size <= limits.maxDirectoryEntries) {
            "离线资料库索引超过容量上限"
        }
        storageCheck(decoded.cards.map { it.summary.code }.distinct().size == decoded.cards.size &&
            decoded.cards.map { it.summary.id }.distinct().size == decoded.cards.size &&
            decoded.cards.map { it.fileName }.distinct().size == decoded.cards.size) { "离线资料库索引包含重复题卡" }
        storageCheck(decoded.directory.map { it.code }.distinct().size == decoded.directory.size &&
            decoded.directory.map { it.id }.distinct().size == decoded.directory.size) { "离线目录索引包含重复记录" }
        var total = 0L
        val directory = decoded.directory.map { entry ->
            validateCode(entry.code)
            validateId(entry.id)
            validateMetadata(entry.title, entry.sourceLabel, emptyList())
            entry.copy(subject = OfflineSubjects.resolve(entry.code, entry.title, entry.sourceLabel, entry.subject))
        }
        val byCode = directory.associateBy { it.code }
        val cards = decoded.cards.map { stored ->
            val row = stored.summary
            validateCode(row.code)
            validateId(row.id)
            validateMetadata(row.title, row.sourceLabel, row.warnings)
            storageCheck(CARD_NAME.matches(stored.fileName) && SHA256.matches(stored.plainSha256)) {
                "离线资料库索引中的文件引用无效"
            }
            storageCheck(stored.cardBytes in 0..limits.maxCardBytes.toLong()) { "离线题卡容量记录无效" }
            total += stored.cardBytes
            stored.copy(summary = row.copy(subject = OfflineSubjects.resolve(
                row.code, row.title, row.sourceLabel, row.subject, byCode[row.code]?.subject,
            )))
        }
        storageCheck(total <= limits.maxTotalBytes) { "离线资料库总容量超过上限" }
        return decoded.copy(cards = cards, directory = directory)
    }

    private fun commit(index: OfflineLibraryIndex) {
        val plain = json.encodeToString(OfflineLibraryIndex.serializer(), index)
        atomicWrite(indexFile, encryptedBytes(plain, MAX_ENCRYPTED_INDEX_BYTES))
    }

    private fun encryptedBytes(plain: String, maximum: Int): ByteArray {
        val encrypted = encrypt(plain).toByteArray(Charsets.UTF_8)
        storageCheck(encrypted.size <= maximum) { "加密后的离线数据超过文件上限" }
        return encrypted
    }

    private fun readBounded(file: File, maximum: Int): String {
        storageCheck(file.isFile && file.length() <= maximum) { "离线资料库文件缺失或过大：${file.name}" }
        val bytes = FileInputStream(file).use { input ->
            val result = java.io.ByteArrayOutputStream(minOf(file.length().toInt(), 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                storageCheck(result.size().toLong() + read <= maximum) { "离线资料库文件过大" }
                result.write(buffer, 0, read)
            }
            result.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun ensureDirectories() {
        storageCheck(root.isDirectory || root.mkdirs()) { "无法创建离线资料库目录" }
        storageCheck(cardDirectory.isDirectory || cardDirectory.mkdir()) { "无法创建离线题卡目录" }
    }

    private fun collectUnused(index: OfflineLibraryIndex) {
        val used = index.cards.mapTo(hashSetOf()) { it.fileName }
        val files = cardDirectory.listFiles() ?: throw OfflineStorageException("无法读取离线题卡目录")
        for (file in files) {
            if ((CARD_NAME.matches(file.name) && file.name !in used) || CARD_TEMP_NAME.matches(file.name)) {
                deleteOwnedCardFile(file)
            }
        }
        val rootFiles = root.listFiles() ?: throw OfflineStorageException("无法读取离线资料库目录")
        for (file in rootFiles.filter { INDEX_TEMP_NAME.matches(it.name) }) {
            deleteOwnedFile(file, root)
        }
    }

    private fun deleteOwnedCardFile(file: File) = deleteOwnedFile(file, cardDirectory)

    private fun deleteOwnedFile(file: File, parent: File) {
        // Never remove a file whose resolved path is outside the dedicated store directory.
        storageCheck(file.canonicalFile.parentFile == parent.canonicalFile) { "拒绝清理存储目录外的文件" }
        storageCheck(!file.exists() || (file.isFile && file.delete())) { "无法清理离线资料库文件：${file.name}" }
    }

    private fun validateCode(code: String) {
        storageCheck(CODE.matches(code) && !code.contains("..")) { "离线题卡编号无效" }
    }

    private fun validateId(id: String) {
        storageCheck(id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl)) { "离线条目标识无效" }
    }

    private fun validateMetadata(title: String, source: String, warnings: List<String>) {
        storageCheck(title.length <= 4096 && source.length <= 4096 && warnings.size <= 64 && warnings.all { it.length <= 4096 }) {
            "离线题卡元数据过长"
        }
    }

    private fun sha256(plain: String): String = MessageDigest.getInstance("SHA-256")
        .digest(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun <T> access(operation: String, block: () -> T): T = synchronized(lock) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: OfflineStorageException) {
            throw error
        } catch (error: Exception) {
            throw OfflineStorageException("$operation 失败：${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private inline fun storageCheck(condition: Boolean, message: () -> String) {
        if (!condition) throw OfflineStorageException(message())
    }

    companion object {
        private val LOCKS = ConcurrentHashMap<String, Any>()
        private val CODE = Regex("[A-Za-z0-9][A-Za-z0-9_.()\\-]{0,127}")
        private val CARD_NAME = Regex("card-[a-f0-9]{32}\\.enc")
        private val CARD_TEMP_NAME = Regex("\\.card-[a-f0-9]{32}\\.enc\\.new-[a-f0-9-]{36}")
        private val INDEX_TEMP_NAME = Regex("\\.index\\.enc\\.new-[a-f0-9-]{36}")
        private val SHA256 = Regex("[a-f0-9]{64}")
        private const val MAX_ENCRYPTED_CARD_BYTES = 18 * 1024 * 1024
        private const val MAX_ENCRYPTED_INDEX_BYTES = 64 * 1024 * 1024
    }
}

@Serializable
private data class OfflineLibraryIndex(
    val version: Int = 1,
    val cards: List<StoredOfflineCard> = emptyList(),
    val directory: List<DirectoryEntry> = emptyList(),
    val legacyMigrationComplete: Boolean = false,
)

@Serializable
private data class StoredOfflineCard(
    val summary: OfflineCardSummary,
    val fileName: String,
    val cardBytes: Long,
    val plainSha256: String,
)

/** A synced candidate plus same-directory atomic rename; failure never truncates the old target. */
internal object OfflineLibraryFileIo {
    fun writeAtomically(target: File, bytes: ByteArray) {
        val parent = target.canonicalFile.parentFile ?: throw IOException("存储文件没有父目录")
        val temporary = File(parent, ".${target.name}.new-${UUID.randomUUID()}")
        if (!temporary.createNewFile()) throw IOException("无法创建存储临时文件")
        var failure: Throwable? = null
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (temporary.exists()) {
                val safe = temporary.canonicalFile.parentFile == parent
                if (!safe || !temporary.delete()) {
                    val cleanup = IOException("无法清理存储临时文件")
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }
}
