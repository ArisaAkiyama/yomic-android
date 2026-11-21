package id.yomic.mobile.data.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesImageDecryptorTest {

    @Test
    fun decryptsValidAes256CbcPayload() {
        val original = ByteArray(128) { it.toByte() }
        val keyHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val ivHex = "abcdef0123456789abcdef0123456789"

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(AesImageDecryptor.hexToBytes(keyHex)!!, "AES"),
            IvParameterSpec(AesImageDecryptor.hexToBytes(ivHex)!!)
        )
        val encrypted = cipher.doFinal(original)
        val decrypted = AesImageDecryptor.decryptAesCbc(encrypted, keyHex, ivHex)

        assertArrayEquals(original, decrypted)
    }

    @Test
    fun returnsOriginalBytesSafelyOnInvalidKeyOrIv() {
        val data = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(data, AesImageDecryptor.decryptAesCbc(data, "not-hex", "1234"))
        assertNull(AesImageDecryptor.hexToBytes("abc"))
        assertNull(AesImageDecryptor.hexToBytes("zzzz"))
    }
}
