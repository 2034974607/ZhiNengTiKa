package app.zhinengtika.nativeapp.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class CredentialVault(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("encrypted_credentials", Context.MODE_PRIVATE)
    private val cipher = DeviceCipher(context)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun accounts(): List<StudentAccount> = readAccounts()

    @Synchronized
    fun currentAccount(): StudentAccount? = readAccounts().firstOrNull()

    @Synchronized
    fun saveAccount(account: StudentAccount): List<StudentAccount> {
        val accounts = readAccounts().filterNot { it.username == account.username }
        val updated = listOf(account) + accounts
        writeAccounts(updated)
        return updated
    }

    @Synchronized
    fun moveToCurrent(index: Int): List<StudentAccount> {
        val accounts = readAccounts()
        require(index in accounts.indices)
        val updated = listOf(accounts[index]) + accounts.filterIndexed { itemIndex, _ -> itemIndex != index }
        writeAccounts(updated)
        return updated
    }

    @Synchronized
    fun removeCurrent(): List<StudentAccount> {
        val updated = readAccounts().drop(1)
        writeAccounts(updated)
        return updated
    }

    private fun readAccounts(): List<StudentAccount> {
        val stored = preferences.getString(KEY, null) ?: return emptyList()
        val plain = cipher.decrypt(stored)
        return json.decodeFromString(ListSerializer(StudentAccount.serializer()), plain)
    }

    private fun writeAccounts(accounts: List<StudentAccount>) {
        val plain = json.encodeToString(ListSerializer(StudentAccount.serializer()), accounts)
        check(preferences.edit().putString(KEY, cipher.encrypt(plain)).commit()) {
            "Could not persist encrypted credentials"
        }
    }

    companion object {
        private const val KEY = "accounts"
    }
}
