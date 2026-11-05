package local.desaysv.apk.installer.adb

import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AdbKeyTest {
    /**
     * adb на компьютере хранит ключ в ~/.android/adbkey, а в adbkey.pub — тот же публичный ключ
     * в формате, который adbd показывает в запросе «Разрешить отладку». Наш кодировщик должен
     * выдать ровно ту же строку, иначе ГУ не примет ключ телефона.
     */
    @Test
    fun publicKeyMatchesAdbFormat() {
        val home = File(System.getProperty("user.home"), ".android")
        val privatePem = File(home, "adbkey")
        val publicAdb = File(home, "adbkey.pub")
        assumeTrue("нет ~/.android/adbkey", privatePem.isFile && publicAdb.isFile)

        val der = Base64.getMimeDecoder().decode(
            privatePem.readText()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .trim(),
        )
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateCrtKey
        val publicKey = factory.generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent))

        val directory = Files.createTempDirectory("adbkey").toFile()
        File(directory, "adbkey.pk8").writeBytes(privateKey.encoded)
        File(directory, "adbkey.x509").writeBytes(publicKey.encoded)
        val key = AdbKey.loadOrCreate(directory)

        val ours = String(key.adbPublicKey("test"), Charsets.UTF_8).substringBefore(' ')
        val expected = publicAdb.readText().trim().substringBefore(' ')
        assertEquals(expected, ours)
        directory.deleteRecursively()
    }

    /** adbd проверяет подпись как RSA_verify(NID_sha1, token): PKCS#1 v1.5 над DigestInfo+token. */
    @Test
    fun signatureIsPkcs1OverSha1DigestInfo() {
        val directory = Files.createTempDirectory("adbkey").toFile()
        val key = AdbKey.loadOrCreate(directory)
        val token = ByteArray(20) { it.toByte() }
        val signature = key.sign(token)
        assertEquals(256, signature.size)

        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(File(directory, "adbkey.x509").readBytes()))
        // Такая подпись проверяется стандартным SHA1withRSA, если токен считать готовым хешем —
        // NONEwithRSA с тем же DigestInfo-префиксом даёт идентичный результат.
        val verifier = Signature.getInstance("NONEwithRSA")
        verifier.initVerify(publicKey)
        verifier.update(
            byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14),
        )
        verifier.update(token)
        assertTrue(verifier.verify(signature))

        // И повторная загрузка возвращает тот же ключ, а не генерирует новый.
        val again = AdbKey.loadOrCreate(directory)
        assertEquals(
            BigInteger(1, key.adbPublicKey("a")),
            BigInteger(1, again.adbPublicKey("a")),
        )
        directory.deleteRecursively()
    }
}
