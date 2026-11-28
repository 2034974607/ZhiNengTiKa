package app.zhinengtika.nativeapp.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

/** One report per user action, regardless of the number of files in the batch. */
data class OfflineImportReport(
    val addedCards: Int,
    val updatedCards: Int,
    val directoryRecords: Int,
    val failedFiles: Int,
    val warnings: List<String>,
    val failures: List<String>,
) {
    fun summary(): String = buildList {
        if (addedCards > 0) add("新增 $addedCards 张")
        if (updatedCards > 0) add("更新 $updatedCards 张")
        if (directoryRecords > 0) add("整理 $directoryRecords 条目录")
        if (failedFiles > 0) add("$failedFiles 个文件失败")
        if (isEmpty()) add("没有可导入的记录")
    }.joinToString("，")

    fun details(): String = buildList {
        add(summary())
        add("已按科目整理；同编号题卡更新现有副本。")
        if (warnings.isNotEmpty()) add("提示：\n" + warnings.joinToString("\n"))
        if (failures.isNotEmpty()) add("失败文件：\n" + failures.joinToString("\n"))
        if (failedFiles > failures.size) add("另有 ${failedFiles - failures.size} 个失败文件未列出。")
    }.joinToString("\n\n")
}

data class OfflineImportProgress(val completed: Int, val total: Int, val label: String)

/**
 * Shared by SAF multi-select and ZIP imports. Only one card is decoded at a time.
 * ZIP contents are read directly; archive paths are never extracted to the filesystem.
 */
class OfflineLibraryImporter(
    private val saveCard: (OfflineTemplateEntry) -> Unit,
    private val saveDirectory: (List<DirectoryEntry>) -> Unit,
    existingCodes: Set<String> = emptySet(),
    knownSubjects: Map<String, String> = emptyMap(),
) {
    private val knownCodes = existingCodes.toMutableSet()
    private val subjects = knownSubjects.toMutableMap()
    private var added = 0
    private var updated = 0
    private var directoryCount = 0
    private var failed = 0
    private val warnings = linkedSetOf<String>()
    private val failures = mutableListOf<String>()

    fun importCard(bytes: ByteArray, sourceName: String, subjectHint: String? = null, fallbackCode: String? = null) {
        attempt(sourceName) {
            val filenameCode = sourceName.substringAfterLast('/').substringBeforeLast('.').trim()
            val imported = TemplateTransfer.importJson(bytes, fallbackCode?.takeIf(String::isNotBlank) ?: filenameCode)
            val card = imported.card
            val subject = OfflineSubjects.resolve(
                code = card.code,
                title = card.title,
                sourcePath = sourceName,
                explicitSubject = subjectHint?.takeIf { OfflineSubjects.normalize(it) != "未分类" }
                    ?: imported.subject,
                knownSubject = subjects[card.code],
            )
            saveCard(OfflineTemplateEntry(
                id = UUID.randomUUID().toString(),
                card = card,
                subject = subject,
                sourceLabel = sourceName.substringAfterLast('/'),
                importedAtMillis = System.currentTimeMillis(),
                warnings = imported.warnings.filterNot { it in TemplateTransfer.importNotices },
            ))
            if (knownCodes.add(card.code)) added++ else updated++
            subjects[card.code] = subject
            imported.warnings.filterNot { it in TemplateTransfer.importNotices }.forEach(::addWarning)
        }
    }

    fun importDirectory(bytes: ByteArray, sourceName: String, subjectHint: String? = null) {
        attempt(sourceName) {
            require(bytes.size <= TemplateTransfer.MAX_IMPORT_BYTES) { "目录文件不能超过 8 MiB" }
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            val parsed = TemplateTransfer.parseLegacyListWithWarnings(text)
            require(parsed.templates.isNotEmpty()) { "没有有效的题卡目录记录" }
            val entries = parsed.templates.map { item ->
                val subject = OfflineSubjects.resolve(
                    item.code, item.title, sourcePath = sourceName,
                    explicitSubject = subjectHint, knownSubject = subjects[item.code],
                )
                DirectoryEntry(UUID.randomUUID().toString(), item.code, item.title, subject, sourceName.substringAfterLast('/'))
            }
            saveDirectory(entries)
            entries.forEach { subjects[it.code] = it.subject }
            directoryCount += entries.size
            parsed.warnings.forEach(::addWarning)
        }
    }

    fun recordFailure(sourceName: String, error: Exception) {
        if (error is CancellationException) throw error
        failed++
        if (failures.size < MAX_DETAILS) {
            failures += "${sourceName.substringAfterLast('/').take(100)}：${error.message.orEmpty().take(240).ifBlank { "读取或保存失败" }}"
        }
    }

    fun report(): OfflineImportReport = OfflineImportReport(
        added, updated, directoryCount, failed, warnings.toList(), failures.toList(),
    )

    fun importZip(
        file: File,
        subjectHint: String? = null,
        onProgress: (OfflineImportProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ) {
        ZipFile(file).use { archive ->
            val all = archive.entries().asSequence().toList()
            require(all.size <= MAX_ZIP_ENTRIES) { "压缩包文件数量超过 $MAX_ZIP_ENTRIES" }
            val files = all.filterNot { it.isDirectory }.onEach {
                val path = it.name.replace('\\', '/')
                require(!path.startsWith('/') && path.split('/').none { part -> part == ".." } && '\u0000' !in path) {
                    "压缩包含无效路径"
                }
            }
            fun path(name: String) = "/" + name.replace('\\', '/')
            val canonical = files.filter { "/题卡JSON/" in path(it.name) && it.name.endsWith(".json", true) }
            val legacy = files.filter { "/templateData/" in path(it.name) }
            val cardFiles = when {
                canonical.isNotEmpty() -> canonical
                legacy.isNotEmpty() -> legacy
                else -> files.filter { it.name.endsWith(".json", true) && it.name.substringAfterLast('/') !in METADATA_NAMES }
            }
            val correctedLists = files.filter { "/科目目录/" in path(it.name) && it.name.endsWith(".txt", true) }
            val oldLists = files.filter { "/templateList/" in path(it.name) }
            val directoryFiles = correctedLists.ifEmpty {
                oldLists.filter { "/all/" in path(it.name) }.ifEmpty { oldLists }
            }
            val selected = directoryFiles + cardFiles
            require(selected.isNotEmpty()) { "压缩包里没有题卡 JSON 或题卡目录" }
            require(selected.size <= MAX_BATCH_FILES) { "单批最多导入 $MAX_BATCH_FILES 个文件" }
            require(selected.all { it.size in 0..TemplateTransfer.MAX_IMPORT_BYTES.toLong() }) {
                "压缩包内的题卡或目录文件超过 8 MiB，或大小无效"
            }
            require(selected.sumOf { it.size } <= MAX_BATCH_BYTES) { "本批题卡解压总量不能超过 256 MiB" }
            selected.forEachIndexed { index, entry ->
                checkCancelled()
                onProgress(OfflineImportProgress(index, selected.size, entry.name.substringAfterLast('/')))
                try {
                    val bytes = archive.getInputStream(entry).use { readBounded(it, TemplateTransfer.MAX_IMPORT_BYTES) }
                    if (index < directoryFiles.size) importDirectory(bytes, entry.name, subjectHint)
                    else importCard(bytes, entry.name, subjectHint)
                } catch (error: Exception) {
                    recordFailure(entry.name, error)
                }
                onProgress(OfflineImportProgress(index + 1, selected.size, entry.name.substringAfterLast('/')))
            }
        }
    }

    private inline fun attempt(sourceName: String, action: () -> Unit) {
        try { action() } catch (error: Exception) { recordFailure(sourceName, error) }
    }

    private fun addWarning(value: String) {
        if (warnings.size < MAX_DETAILS) warnings += value
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
        const val MAX_BATCH_FILES = 10_000
        private const val MAX_ZIP_ENTRIES = 20_000
        private const val MAX_BATCH_BYTES = 256L * 1024 * 1024
        private const val MAX_DETAILS = 20
        private val METADATA_NAMES = setOf("题卡索引.json", "提取清单.json", "验证报告.json", "manifest.json")

        fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "文件超过 ${maxBytes / (1024 * 1024)} MiB" }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
