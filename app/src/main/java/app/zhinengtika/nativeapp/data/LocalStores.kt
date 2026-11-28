package app.zhinengtika.nativeapp.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class TemplateCache(context: Context) {
    private val root = File(context.applicationContext.filesDir, "template-cache").apply { mkdirs() }
    private val cipher = DeviceCipher(context)
    private val json = Json { ignoreUnknownKeys = true }

    fun read(code: String, account: StudentAccount): TemplateCard? = synchronized(LOCK) {
        runCatching {
            val file = cacheFile(code, account)
            if (!file.isFile) null
            else json.decodeFromString(TemplateCard.serializer(), cipher.decrypt(AtomicFile(file).readFully().toString(Charsets.UTF_8)))
        }.getOrNull()
    }

    fun write(card: TemplateCard, account: StudentAccount) = synchronized(LOCK) {
        check(root.isDirectory || root.mkdirs()) { "Could not create template cache" }
        val content = cipher.encrypt(json.encodeToString(TemplateCard.serializer(), card))
        val atomic = AtomicFile(cacheFile(card.code, account))
        val output = atomic.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    fun clearAll() = synchronized(LOCK) {
        if (root.exists()) check(root.deleteRecursively()) { "Could not clear template cache" }
        check(root.mkdirs()) { "Could not recreate template cache" }
    }

    private fun cacheFile(code: String, account: StudentAccount): File {
        val scope = "${account.schoolId}\u0000${account.studentId}\u0000$code"
        val digest = sha256(scope)
        return File(root, "$digest.json.enc")
    }

    companion object {
        private val LOCK = Any()
    }
}

@Serializable
private data class ScopedHistory(
    val accountScope: String,
    val entries: List<TemplateHistoryEntry>,
)

class HistoryStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("encrypted_history", Context.MODE_PRIVATE)
    private val cipher = DeviceCipher(context)
    private val json = Json { ignoreUnknownKeys = true }

    fun list(account: StudentAccount): List<TemplateHistoryEntry> = synchronized(LOCK) {
        readAll()[accountScope(account)].orEmpty()
    }

    fun add(card: TemplateCard, account: StudentAccount): List<TemplateHistoryEntry> = synchronized(LOCK) {
        val all = readAll().toMutableMap()
        val scope = accountScope(account)
        val updated = (all[scope].orEmpty().filterNot { it.code == card.code } +
            TemplateHistoryEntry(card.code, card.title, System.currentTimeMillis()))
            .sortedByDescending(TemplateHistoryEntry::viewedAtMillis)
            .take(MAX_ENTRIES)
        all[scope] = updated
        writeAll(all)
        updated
    }

    fun clear() = synchronized(LOCK) {
        check(preferences.edit().remove(KEY).commit()) { "Could not clear encrypted history" }
    }

    private fun readAll(): Map<String, List<TemplateHistoryEntry>> {
        val stored = preferences.getString(KEY, null) ?: return emptyMap()
        val scoped = json.decodeFromString(ListSerializer(ScopedHistory.serializer()), cipher.decrypt(stored))
        return scoped.associate { it.accountScope to it.entries }
    }

    private fun writeAll(all: Map<String, List<TemplateHistoryEntry>>) {
        val scoped = all.map { (scope, entries) -> ScopedHistory(scope, entries) }
        if (scoped.isEmpty()) {
            check(preferences.edit().remove(KEY).commit()) { "Could not clear encrypted history" }
        } else {
            val encrypted = cipher.encrypt(json.encodeToString(ListSerializer(ScopedHistory.serializer()), scoped))
            check(preferences.edit().putString(KEY, encrypted).commit()) {
                "Could not persist encrypted history"
            }
        }
    }

    companion object {
        private const val KEY = "templates"
        private const val MAX_ENTRIES = 200
        private val LOCK = Any()
    }
}

internal fun accountScope(account: StudentAccount): String =
    sha256("${account.schoolId}\u0000${account.studentId}")

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
