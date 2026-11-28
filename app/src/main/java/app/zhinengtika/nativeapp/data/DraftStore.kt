package app.zhinengtika.nativeapp.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class StoredAnswerDraft(
    val questionId: String,
    val selected: Set<String> = emptySet(),
    val photoUris: List<String> = emptyList(),
    val existingImageUrls: List<String> = emptyList(),
)

class DraftStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("encrypted_answer_drafts", Context.MODE_PRIVATE)
    private val cipher = DeviceCipher(context)
    private val json = Json { ignoreUnknownKeys = true }

    fun read(code: String, account: StudentAccount): List<StoredAnswerDraft> = synchronized(LOCK) {
        val value = preferences.getString(storageKey(code, account), null) ?: return@synchronized emptyList()
        json.decodeFromString(ListSerializer(StoredAnswerDraft.serializer()), cipher.decrypt(value))
    }

    fun write(code: String, account: StudentAccount, drafts: List<StoredAnswerDraft>) = synchronized(LOCK) {
        val encrypted = cipher.encrypt(json.encodeToString(ListSerializer(StoredAnswerDraft.serializer()), drafts))
        check(preferences.edit().putString(storageKey(code, account), encrypted).commit()) {
            "Could not save encrypted answer draft"
        }
    }

    fun clear(code: String, account: StudentAccount) = synchronized(LOCK) {
        check(preferences.edit().remove(storageKey(code, account)).commit()) { "Could not clear answer draft" }
    }

    private fun storageKey(code: String, account: StudentAccount): String =
        accountScope(account) + ":" + android.net.Uri.encode(code)

    companion object {
        private val LOCK = Any()
    }
}
