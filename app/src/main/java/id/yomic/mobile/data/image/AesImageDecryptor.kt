package id.yomic.mobile.data.image

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts AES-256-CBC (PKCS5Padding) encrypted comic page images used by sources like MangaMillion.
 * Mirrors WebResourceImageProxy.DecryptAes256Cbc in Yomic Desktop.
 */
object AesImageDecryptor {

    fun decryptAesCbc(encryptedBytes: ByteArray, hexKey: String, hexIv: String): ByteArray {
        if (encryptedBytes.isEmpty()) return encryptedBytes
        val keyBytes = hexToBytes(hexKey) ?: return encryptedBytes
        val ivBytes = hexToBytes(hexIv) ?: return encryptedBytes
        if (ivBytes.size != 16 || (keyBytes.size != 16 && keyBytes.size != 24 && keyBytes.size != 32)) {
            return encryptedBytes
        }
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            cipher.doFinal(encryptedBytes)
        }.getOrElse { encryptedBytes }
    }

    fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi == -1 || lo == -1) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
