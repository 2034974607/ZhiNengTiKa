package app.zhinengtika.nativeapp.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Offline-only, account-independent library. Entries here are never an authenticated server
 * response and are never a source of submission authority. No credential fields are stored.
 */
@Serializable
data class OfflineTemplateEntry(
    val id: String,
    val card: TemplateCard,
    val subject: String,
    val sourceLabel: String,
    val isDemo: Boolean = false,
    val importedAtMillis: Long = 0L,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class DirectoryEntry(
    val id: String,
    val code: String,
    val title: String,
    val subject: String,
    val sourceLabel: String,
)

class OfflineTemplateStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("encrypted_offline_library", Context.MODE_PRIVATE)
    private val cipher = DeviceCipher(context)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Writing all cards to one SharedPreferences entry rewrites the whole blob on each save.
     * Keep a conservative aggregate budget so a few 8 MiB imports cannot blow up memory/storage.
     */
    fun canAcceptCard(incomingBytes: Int): Boolean = synchronized(LOCK) {
        readCardsUnsafe().size < MAX_CARDS && totalUtf8BytesUnsafe() + incomingBytes <= MAX_TOTAL_BYTES
    }

    fun canAcceptDirectory(incomingCount: Int): Boolean = synchronized(LOCK) {
        readDirectoryUnsafe().size + incomingCount <= MAX_DIRECTORY_ENTRIES
    }

    private fun totalUtf8BytesUnsafe(): Int = readCardsUnsafe().sumOf { entry ->
        runCatching { json.encodeToString(TemplateCard.serializer(), entry.card).toByteArray(Charsets.UTF_8).size }
            .getOrDefault(TemplateTransfer.MAX_IMPORT_BYTES)
    }

    fun cards(): List<OfflineTemplateEntry> = synchronized(LOCK) {
        read(KEY_CARDS) { json.decodeFromString(ListSerializer(OfflineTemplateEntry.serializer()), it) }.orEmpty()
    }

    fun directory(): List<DirectoryEntry> = synchronized(LOCK) {
        read(KEY_DIRECTORY) { json.decodeFromString(ListSerializer(DirectoryEntry.serializer()), it) }.orEmpty()
    }

    fun saveCard(entry: OfflineTemplateEntry) = synchronized(LOCK) {
        val current = readCardsUnsafe()
        // Enforce budget inside the lock too; the UI pre-check can go stale under concurrency.
        val replacing = current.any { it.id == entry.id }
        val incomingBytes = runCatching {
            json.encodeToString(TemplateCard.serializer(), entry.card).toByteArray(Charsets.UTF_8).size
        }.getOrDefault(TemplateTransfer.MAX_IMPORT_BYTES)
        val otherBytes = current.filterNot { it.id == entry.id }.sumOf { existing ->
            runCatching { json.encodeToString(TemplateCard.serializer(), existing.card).toByteArray(Charsets.UTF_8).size }
                .getOrDefault(TemplateTransfer.MAX_IMPORT_BYTES)
        }
        require((if (replacing) current.size else current.size + 1) <= MAX_CARDS) {
            "离线资料库卡片数量已达上限"
        }
        require(otherBytes + incomingBytes <= MAX_TOTAL_BYTES) {
            "离线资料库总容量已达上限"
        }
        val updated = current.filterNot { it.id == entry.id } + entry
        writeCardsUnsafe(updated)
    }

    fun saveDirectory(entries: List<DirectoryEntry>) = synchronized(LOCK) {
        val existing = readDirectoryUnsafe()
        val known = existing.map { "${it.code}\u0000${it.title}\u0000${it.subject}" }.toSet()
        val added = entries.filterNot { "${it.code}\u0000${it.title}\u0000${it.subject}" in known }
        require(existing.size + added.size <= MAX_DIRECTORY_ENTRIES) {
            "离线目录条目数已达上限"
        }
        writeDirectoryUnsafe(existing + added)
    }

    fun deleteCard(id: String) = synchronized(LOCK) {
        writeCardsUnsafe(readCardsUnsafe().filterNot { it.id == id })
    }

    fun deleteDirectory(id: String) = synchronized(LOCK) {
        writeDirectoryUnsafe(readDirectoryUnsafe().filterNot { it.id == id })
    }

    fun clearAll() = synchronized(LOCK) {
        check(preferences.edit().remove(KEY_CARDS).remove(KEY_DIRECTORY).commit()) {
            "Could not clear offline library"
        }
    }

    private fun readCardsUnsafe(): List<OfflineTemplateEntry> =
        read(KEY_CARDS) { json.decodeFromString(ListSerializer(OfflineTemplateEntry.serializer()), it) }.orEmpty()

    private fun readDirectoryUnsafe(): List<DirectoryEntry> =
        read(KEY_DIRECTORY) { json.decodeFromString(ListSerializer(DirectoryEntry.serializer()), it) }.orEmpty()

    private fun writeCardsUnsafe(value: List<OfflineTemplateEntry>) {
        val key = if (value.isEmpty()) preferences.edit().remove(KEY_CARDS)
        else preferences.edit().putString(KEY_CARDS, cipher.encrypt(
            json.encodeToString(ListSerializer(OfflineTemplateEntry.serializer()), value)
        ))
        check(key.commit()) { "Could not save offline template" }
    }

    private fun writeDirectoryUnsafe(value: List<DirectoryEntry>) {
        val editor = if (value.isEmpty()) preferences.edit().remove(KEY_DIRECTORY)
        else preferences.edit().putString(KEY_DIRECTORY, cipher.encrypt(
            json.encodeToString(ListSerializer(DirectoryEntry.serializer()), value)
        ))
        check(editor.commit()) { "Could not save offline directory" }
    }

    private fun <T> read(key: String, decode: (String) -> T): T? {
        val stored = preferences.getString(key, null) ?: return null
        return decode(cipher.decrypt(stored))
    }

    companion object {
        private const val KEY_CARDS = "cards"
        private const val KEY_DIRECTORY = "directory"
        private const val MAX_CARDS = 100
        private const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
        private const val MAX_DIRECTORY_ENTRIES = 10_000
        private val LOCK = Any()
    }
}
