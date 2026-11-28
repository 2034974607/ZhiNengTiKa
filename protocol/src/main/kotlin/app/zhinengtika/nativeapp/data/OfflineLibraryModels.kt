package app.zhinengtika.nativeapp.data

import kotlinx.serialization.Serializable

/** Offline content is independent of accounts and never grants online submission authority. */
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

/** List rows do not deserialize, decrypt, or retain every question in the library. */
@Serializable
data class OfflineCardSummary(
    val id: String,
    val code: String,
    val title: String,
    val subject: String,
    val sourceLabel: String,
    val isDemo: Boolean = false,
    val importedAtMillis: Long = 0L,
    val warnings: List<String> = emptyList(),
)

/** Read both old keys before returning a snapshot; clear only after the new index is committed. */
interface LegacyOfflineLibrarySource {
    fun read(): LegacyOfflineLibrarySnapshot?
    fun clear()
}

data class LegacyOfflineLibrarySnapshot(
    val cards: List<OfflineTemplateEntry>,
    val directory: List<DirectoryEntry>,
)

data class OfflineLibraryLimits(
    val maxCards: Int = 10_000,
    val maxTotalBytes: Long = 512L * 1024 * 1024,
    val maxDirectoryEntries: Int = 10_000,
    val maxCardBytes: Int = 8 * 1024 * 1024,
) {
    init {
        require(maxCards > 0 && maxTotalBytes > 0 && maxDirectoryEntries > 0 && maxCardBytes > 0)
        require(maxCardBytes <= 8 * 1024 * 1024) { "单卡上限不能超过 8 MiB" }
    }
}
