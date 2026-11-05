package local.desaysv.apk.installer.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * RSA-ключ телефона для авторизации на ГУ. Хранится постоянно: если на ГУ один раз отметить
 * «Всегда разрешать с этого компьютера», повторного запроса не будет.
 */
class AdbKey private constructor(
    private val privateKey: PrivateKey,
    private val publicKey: RSAPublicKey,
) {
    /**
     * adbd проверяет подпись токена как RSA_verify(NID_sha1, token): токен уже считается
     * SHA-1 хешем, поэтому подписываем DigestInfo(SHA-1) + токен без повторного хеширования.
     */
    fun sign(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("NONEwithRSA")
        signature.initSign(privateKey)
        signature.update(SHA1_DIGEST_INFO)
        signature.update(token)
        return signature.sign()
    }

    /** Публичный ключ в формате adb (RSAPublicKey из libcrypto_utils), base64 + имя + NUL. */
    fun adbPublicKey(name: String): ByteArray {
        val modulus = publicKey.modulus
        val words = KEY_BITS / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(modulus.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(modulus)

        val buffer = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(words)
        buffer.putInt(n0inv.toLong().toInt())
        buffer.put(littleEndian(modulus, KEY_BYTES))
        buffer.put(littleEndian(rr, KEY_BYTES))
        buffer.putInt(publicKey.publicExponent.toInt())

        val encoded = Base64.getEncoder().encodeToString(buffer.array())
        return "$encoded $name\u0000".toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val KEY_BITS = 2048
        private const val KEY_BYTES = KEY_BITS / 8
        private const val PRIVATE_FILE = "adbkey.pk8"
        private const val PUBLIC_FILE = "adbkey.x509"

        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        fun loadOrCreate(directory: File): AdbKey {
            val privateFile = File(directory, PRIVATE_FILE)
            val publicFile = File(directory, PUBLIC_FILE)
            if (privateFile.isFile && publicFile.isFile) {
                try {
                    val factory = KeyFactory.getInstance("RSA")
                    val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
                    val publicKey = factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
                    return AdbKey(privateKey, publicKey as RSAPublicKey)
                } catch (_: Exception) {
                    // Повреждённый ключ просто создаём заново — ГУ один раз спросит разрешение.
                }
            }

            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(KEY_BITS)
            val pair = generator.generateKeyPair()
            directory.mkdirs()
            writeAtomically(privateFile, pair.private.encoded)
            writeAtomically(publicFile, pair.public.encoded)
            return AdbKey(pair.private, pair.public as RSAPublicKey)
        }

        private fun writeAtomically(target: File, bytes: ByteArray) {
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) {
                    throw IllegalStateException("Не удалось сохранить ключ ADB")
                }
            }
        }

        private fun littleEndian(value: BigInteger, size: Int): ByteArray {
            val bigEndian = value.toByteArray()
            val result = ByteArray(size)
            for (index in 0 until size) {
                val source = bigEndian.size - 1 - index
                if (source >= 0) {
                    result[index] = bigEndian[source]
                }
            }
            return result
        }
    }
}
