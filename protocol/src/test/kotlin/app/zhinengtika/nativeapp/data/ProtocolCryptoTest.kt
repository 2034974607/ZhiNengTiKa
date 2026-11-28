package app.zhinengtika.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolCryptoTest {
    @Test
    fun requestDigestMatchesSyntheticVector() {
        assertEquals(
            "4fd5e03b6ed82244e3e682ac81ac1ee6",
            ProtocolCrypto.requestDigest("1700000000000", "test-session"),
        )
    }

    @Test
    fun aesRoundTripsUtf8AndBlockBoundaries() {
        listOf("", "abc", "中文题目", "1234567890123456", "more than one AES block 文字").forEach { value ->
            assertEquals(value, ProtocolCrypto.decryptFromBase64(ProtocolCrypto.encryptToBase64(value)))
        }
    }
}
