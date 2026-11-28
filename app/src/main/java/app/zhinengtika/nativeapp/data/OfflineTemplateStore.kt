package app.zhinengtika.nativeapp.data

import android.content.Context
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Android Keystore / legacy preferences adapter for the platform-independent file engine. */
class OfflineTemplateStore(context: Context) {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("encrypted_offline_library", Context.MODE_PRIVATE)
    private val cipher = DeviceCipher(application)
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = FileOfflineTemplateStore(
        rootDirectory = File(application.filesDir, "offline-library-v2"),
        encrypt = cipher::encrypt,
        decrypt = cipher::decrypt,
        legacySource = object : LegacyOfflineLibrarySource {
            override fun read(): LegacyOfflineLibrarySnapshot? {
                val oldCards = preferences.getString(KEY_CARDS, null)
                val oldDirectory = preferences.getString(KEY_DIRECTORY, null)
                if (oldCards == null && oldDirectory == null) return null
                // Decode both before migration starts. Malformed/undecryptable keys remain intact.
                val cards = oldCards?.let {
                    json.decodeFromString(ListSerializer(OfflineTemplateEntry.serializer()), cipher.decrypt(it))
                }.orEmpty()
                val directory = oldDirectory?.let {
                    json.decodeFromString(ListSerializer(DirectoryEntry.serializer()), cipher.decrypt(it))
                }.orEmpty()
                return LegacyOfflineLibrarySnapshot(cards, directory)
            }

            override fun clear() {
                if (preferences.contains(KEY_CARDS) || preferences.contains(KEY_DIRECTORY)) {
                    check(preferences.edit().remove(KEY_CARDS).remove(KEY_DIRECTORY).commit()) {
                        "新题卡库已持久化，但旧离线资料键清理失败；下次访问会重试清理"
                    }
                }
            }
        },
    )

    /** Read lightweight metadata for lists; no question bodies are loaded. Run on an IO dispatcher. */
    fun summaries(): List<OfflineCardSummary> = engine.summaries()
    fun card(id: String): OfflineTemplateEntry? = engine.card(id)
    fun cards(): List<OfflineTemplateEntry> = engine.cards()
    fun directory(): List<DirectoryEntry> = engine.directory()
    fun saveCard(entry: OfflineTemplateEntry) = engine.saveCard(entry)
    fun saveCards(entries: Sequence<OfflineTemplateEntry>) = engine.saveCards(entries)
    fun saveDirectory(entries: List<DirectoryEntry>) = engine.saveDirectory(entries)
    fun deleteCard(id: String) = engine.deleteCard(id)
    fun deleteDirectory(id: String) = engine.deleteDirectory(id)
    fun clearAll() = engine.clearAll()

    // Retained for source compatibility. Saving enforces capacity after resolving duplicate codes.
    fun canAcceptCard(incomingBytes: Int): Boolean = engine.canAcceptCard(incomingBytes)
    fun canAcceptDirectory(incomingCount: Int): Boolean = engine.canAcceptDirectory(incomingCount)

    companion object {
        private const val KEY_CARDS = "cards"
        private const val KEY_DIRECTORY = "directory"
    }
}
