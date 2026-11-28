package app.zhinengtika.nativeapp.data

import java.util.Locale

/** Canonical subject classification for legacy and imported offline cards. */
object OfflineSubjects {
    const val UNCATEGORIZED = "未分类"

    /** Stable display/filter order; unclassified entries always appear last. */
    val ordered: List<String> = listOf("语文", "数学", "英语", "物理", "化学", "生物", UNCATEGORIZED)

    private val aliases = mapOf(
        "语文" to "语文", "chinese" to "语文", "中文" to "语文",
        "数学" to "数学", "math" to "数学", "maths" to "数学", "mathematics" to "数学",
        "英语" to "英语", "english" to "英语",
        "物理" to "物理", "physics" to "物理",
        "化学" to "化学", "chemistry" to "化学",
        "生物" to "生物", "biology" to "生物", "biography" to "生物", "bio" to "生物",
        "other" to UNCATEGORIZED, "others" to UNCATEGORIZED,
        "未分类" to UNCATEGORIZED, "uncategorized" to UNCATEGORIZED,
        "unclassified" to UNCATEGORIZED, "unknown" to UNCATEGORIZED,
        "其他" to UNCATEGORIZED, "其它" to UNCATEGORIZED,
    )

    /** Known English aliases and Chinese names normalize; unknown values never guess. */
    fun normalize(value: String?): String {
        val key = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return aliases[key] ?: UNCATEGORIZED
    }

    /**
     * Resolves in confidence order: explicit concrete choice, exact legacy code,
     * existing concrete category, subject-bearing path, then unambiguous title.
     * A blank/other/unclassified hint is intentionally non-blocking.
     */
    fun resolve(
        code: String?,
        title: String,
        sourcePath: String? = null,
        explicitSubject: String? = null,
        knownSubject: String? = null,
    ): String {
        normalize(explicitSubject).takeIf { it != UNCATEGORIZED }?.let { return it }

        LegacyOfflineSubjectIndex.subjectFor(code)?.let { return it }

        normalize(knownSubject).takeIf { it != UNCATEGORIZED }?.let { return it }
        subjectInPath(sourcePath)?.let { return it }
        subjectInTitle(title)?.let { return it }
        return UNCATEGORIZED
    }

    /** Normalizes, de-duplicates, and orders only the supplied categories. */
    fun order(values: Iterable<String>): List<String> {
        val present = values.map(::normalize).toSet()
        return ordered.filter { it in present }
    }

    private fun subjectInPath(sourcePath: String?): String? {
        if (sourcePath.isNullOrBlank()) return null
        val found = sourcePath
            .replace('\\', '/')
            .split('/', '_', '-')
            .asSequence()
            .map { it.substringBeforeLast('.').trim() }
            .map(::normalize)
            .filter { it != UNCATEGORIZED }
            .toSet()
        return found.singleOrNull()
    }

    private fun subjectInTitle(title: String): String? {
        if (title.isBlank()) return null
        val found = linkedSetOf<String>()
        listOf("语文", "中文", "数学", "英语", "物理", "化学", "生物").forEach { token ->
            if (title.contains(token)) found += normalize(token)
        }
        val englishAliases = mapOf(
            "chinese" to "语文", "math" to "数学", "maths" to "数学", "mathematics" to "数学",
            "english" to "英语", "physics" to "物理", "chemistry" to "化学",
            "biology" to "生物", "biography" to "生物",
        )
        englishAliases.forEach { (alias, subject) ->
            val pattern = Regex("(?<![A-Za-z])${Regex.escape(alias)}(?![A-Za-z])", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(title)) found += subject
        }
        return found.singleOrNull()
    }
}
