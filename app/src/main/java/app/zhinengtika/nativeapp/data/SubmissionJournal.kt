package app.zhinengtika.nativeapp.data

import android.content.Context
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class SubmissionMarker(
    val scopeHash: String,
    val templateCode: String,
    val attemptId: String,
    val updatedAtMillis: Long,
)

class SubmissionJournal(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("encrypted_submission_state", Context.MODE_PRIVATE)
    private val cipher = DeviceCipher(context)
    private val json = Json { ignoreUnknownKeys = true }

    fun hasUnresolvedSubmission(code: String, account: StudentAccount): Boolean = synchronized(LOCK) {
        read().any { it.scopeHash == accountScope(account) && it.templateCode == code }
    }

    fun tryBegin(code: String, account: StudentAccount): String? = synchronized(LOCK) {
        val scope = accountScope(account)
        val markers = read()
        if (markers.any { it.scopeHash == scope && it.templateCode == code }) return@synchronized null
        val attemptId = UUID.randomUUID().toString()
        write(markers + SubmissionMarker(scope, code, attemptId, System.currentTimeMillis()))
        IN_FLIGHT.add(attemptId)
        attemptId
    }

    fun resolve(code: String, account: StudentAccount, attemptId: String? = null) = synchronized(LOCK) {
        val scope = accountScope(account)
        val markers = read()
        val matches = markers.filter { it.scopeHash == scope && it.templateCode == code }
        if (attemptId == null) {
            check(matches.none { it.attemptId in IN_FLIGHT }) { "该提交仍在执行，请稍后核实" }
        }
        write(markers.filterNot {
            it.scopeHash == scope && it.templateCode == code && (attemptId == null || it.attemptId == attemptId)
        })
    }

    fun finishAttempt(attemptId: String) = synchronized(LOCK) {
        IN_FLIGHT.remove(attemptId)
        Unit
    }

    private fun read(): List<SubmissionMarker> {
        val stored = preferences.getString(KEY, null) ?: return emptyList()
        return json.decodeFromString(ListSerializer(SubmissionMarker.serializer()), cipher.decrypt(stored))
    }

    private fun write(markers: List<SubmissionMarker>) {
        if (markers.isEmpty()) {
            check(preferences.edit().remove(KEY).commit()) { "Could not update submission state" }
        } else {
            val encrypted = cipher.encrypt(json.encodeToString(ListSerializer(SubmissionMarker.serializer()), markers))
            check(preferences.edit().putString(KEY, encrypted).commit()) { "Could not update submission state" }
        }
    }

    companion object {
        private const val KEY = "pending"
        private val LOCK = Any()
        private val IN_FLIGHT = mutableSetOf<String>()
    }
}
