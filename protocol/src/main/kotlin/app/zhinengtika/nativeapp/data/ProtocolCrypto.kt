package app.zhinengtika.nativeapp.data

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object ProtocolCrypto {
    private const val AES_KEY = "6f0c5ba452b24fa28989e9524d77407a"
    private val keyBytes = AES_KEY.toByteArray(StandardCharsets.US_ASCII)

    init {
        require(keyBytes.size == 32) { "Invalid protocol AES key length" }
    }

    fun encryptToBase64(value: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decryptFromBase64(value: String): String {
        val encrypted = Base64.getDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        return cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
    }

    fun requestDigest(timestamp: String, clientSession: String): String {
        val input = "jbyxinjiaoyu$timestamp$clientSession" + "android"
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
