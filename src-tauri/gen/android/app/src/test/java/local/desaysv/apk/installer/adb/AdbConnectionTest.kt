package local.desaysv.apk.installer.adb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Клиент ADB против поддельного adbd: проверяет кадрирование, управление потоком WRTE/OKAY,
 * разбор shell_v2 на рваных границах, sync push с маленьким maxdata и авторизацию ключом.
 */
class AdbConnectionTest {
    private lateinit var device: FakeAdbd
    private lateinit var key: AdbKey

    @Before
    fun setUp() {
        key = AdbKey.loadOrCreate(Files.createTempDirectory("adbkey").toFile())
    }

    @After
    fun tearDown() {
        device.close()
    }

    private fun connect(): AdbConnection {
        var prompted = false
        val connection = AdbConnection.connect(
            TcpAdbTransport("127.0.0.1", device.port),
            key,
            "test@phone",
            5_000,
            5_000,
        ) { prompted = true }
        assertEquals(device.secure, prompted)
        return connection
    }

    @Test
    fun secureDeviceAcceptsPublicKeyAndMatchingSignature() {
        device = FakeAdbd(secure = true, maxData = 4096)
        connect().use { connection ->
            assertEquals("DesaySV", connection.properties["ro.product.model"])
            assertTrue(connection.features.contains("shell_v2"))
            assertEquals(4096, connection.maxPayload)
        }
        // Так adbd проверит подпись при следующем подключении, когда ключ уже сохранён.
        assertTrue("подпись токена не проходит RSA_verify с переданным ключом", device.signatureVerified.get())
    }

    @Test
    fun shellReturnsSeparateStreamsAndExitCode() {
        device = FakeAdbd(secure = false, maxData = 4096)
        connect().use { connection ->
            val result = connection.shell("pm grant pkg perm", 5_000)
            assertEquals("shell,v2,raw:pm grant pkg perm", device.lastService.get())
            assertEquals(FakeAdbd.STDOUT, result.stdout)
            assertEquals("Exception: unknown permission\n", result.stderr)
            assertEquals(3, result.exitCode)
            assertTrue("stdin не закрыт, как у adb с /dev/null", device.stdinClosed.get())

            // Соединение остаётся пригодным для следующих команд.
            val again = connection.shell("echo ok", 5_000)
            assertEquals(3, again.exitCode)
        }
    }

    @Test
    fun pushSendsWholeFileInSmallPackets() {
        device = FakeAdbd(secure = false, maxData = 4096)
        val data = ByteArray(300_000).also { Random(1).nextBytes(it) }
        connect().use { connection ->
            connection.push(ByteArrayInputStream(data), "/data/local/tmp/app.apk", 0x81a4, 1_700_000_000, 10_000)
        }
        assertEquals("/data/local/tmp/app.apk,33188", device.pushedTarget.get())
        assertArrayEquals(data, device.pushedData.get())
        assertEquals(1_700_000_000, device.pushedMtime.get())
    }

    @Test
    fun pushReportsDeviceFailure() {
        device = FakeAdbd(secure = false, maxData = 4096, failPush = "couldn't create file: Permission denied")
        connect().use { connection ->
            try {
                connection.push(ByteArrayInputStream(ByteArray(10_000)), "/system/app.apk", 0x81a4, 0, 10_000)
                fail("ожидалась ошибка копирования")
            } catch (error: IOException) {
                assertTrue(error.message, error.message!!.contains("Permission denied"))
            }
            // После FAIL соединение живо и можно продолжать.
            assertEquals(3, connection.shell("id", 5_000).exitCode)
        }
    }

    @Test
    fun refusedServiceIsAnError() {
        device = FakeAdbd(secure = false, maxData = 4096)
        connect().use { connection ->
            try {
                connection.open("unknown:")
                fail("ожидался отказ сервиса")
            } catch (error: IOException) {
                assertTrue(error.message, error.message!!.contains("отклонило"))
            }
        }
    }

    @Test
    fun disconnectFailsPendingCommand() {
        device = FakeAdbd(secure = false, maxData = 4096, hangShell = true)
        connect().use { connection ->
            Thread {
                Thread.sleep(300)
                device.close()
            }.start()
            try {
                connection.shell("sleep 100", 10_000)
                fail("ожидалась ошибка обрыва")
            } catch (error: IOException) {
                assertTrue(error.message, error.message!!.contains("потеряно"))
            }
            assertTrue(!connection.isAlive)
        }
    }
}

/** Упрощённый adbd по protocol.txt: одно соединение, потоки обслуживаются по одному. */
private class FakeAdbd(
    val secure: Boolean,
    private val maxData: Int,
    private val failPush: String? = null,
    private val hangShell: Boolean = false,
) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    @Volatile
    private var socket: Socket? = null

    val lastService = AtomicReference<String>()
    val stdinClosed = AtomicReference(false)
    val signatureVerified = AtomicReference(false)
    val pushedTarget = AtomicReference<String>()
    val pushedData = AtomicReference<ByteArray>()
    val pushedMtime = AtomicReference<Int>()

    private val thread = Thread { serve() }.apply { isDaemon = true; start() }

    override fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        server.close()
    }

    private class Msg(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var nextRemoteId = 100

    private fun read(): Msg {
        val header = ByteArray(24)
        input.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val length = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        check(magic == command.inv()) { "bad magic" }
        val data = ByteArray(length)
        input.readFully(data)
        check(checksum == data.sumOf { it.toInt() and 0xff }) { "bad checksum" }
        return Msg(command, arg0, arg1, data)
    }

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        check(data.size <= maxData) { "device payload too big" }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 0xff }).putInt(command.inv())
        synchronized(this) {
            output.write(header.array())
            output.write(data)
            output.flush()
        }
    }

    private fun serve() {
        try {
            val client = server.accept()
            socket = client
            input = DataInputStream(client.getInputStream())
            output = client.getOutputStream()
            handshake()
            while (true) {
                val message = read()
                if (message.command != AdbConnection.A_OPEN) {
                    // CLSE/OKAY по уже закрытым потокам adbd просто игнорирует.
                    continue
                }
                val service = String(message.data, Charsets.UTF_8).trimEnd('\u0000')
                lastService.set(service)
                val local = message.arg0
                when {
                    service.startsWith("shell,v2,raw:") -> shell(local)
                    service == "sync:" -> sync(local)
                    else -> send(AdbConnection.A_CLSE, 0, local)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handshake() {
        val connect = read()
        check(connect.command == AdbConnection.A_CNXN)
        check(String(connect.data).startsWith("host::features=")) { "bad host banner" }
        check(connect.arg1 >= maxData)
        if (secure) {
            val token = ByteArray(20) { (it * 7).toByte() }
            send(AdbConnection.A_AUTH, 1, 0, token)
            val signature = read()
            check(signature.command == AdbConnection.A_AUTH && signature.arg0 == 2)
            // Ключ телефона ещё неизвестен — просим публичный.
            send(AdbConnection.A_AUTH, 1, 0, token)
            val publicKey = read()
            check(publicKey.command == AdbConnection.A_AUTH && publicKey.arg0 == 3)
            signatureVerified.set(verify(publicKey.data, token, signature.data))
        }
        send(
            AdbConnection.A_CNXN,
            0x01000001,
            maxData,
            "device::ro.product.name=g7ph_t22;ro.product.model=DesaySV;features=shell_v2,cmd\u0000".toByteArray(),
        )
    }

    /** Разбирает ключ так же, как adbd (android_pubkey_decode), и делает RSA_verify(NID_sha1). */
    private fun verify(encoded: ByteArray, token: ByteArray, signature: ByteArray): Boolean {
        val text = String(encoded, Charsets.UTF_8).trimEnd('\u0000')
        val raw = Base64.getDecoder().decode(text.substringBefore(' '))
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val words = buffer.int
        val n0inv = buffer.int
        val modulusLe = ByteArray(words * 4).also { buffer.get(it) }
        val rrLe = ByteArray(words * 4).also { buffer.get(it) }
        val exponent = buffer.int
        val modulus = BigInteger(1, modulusLe.reversedArray())
        val r32 = BigInteger.ONE.shiftLeft(32)
        check(BigInteger.valueOf(n0inv.toLong() and 0xffffffffL) == r32.subtract(modulus.mod(r32).modInverse(r32)))
        check(BigInteger(1, rrLe.reversedArray()) == BigInteger.ONE.shiftLeft(words * 64).mod(modulus))
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(exponent.toLong())))
        val verifier = Signature.getInstance("NONEwithRSA")
        verifier.initVerify(publicKey)
        verifier.update(byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14))
        verifier.update(token)
        return verifier.verify(signature)
    }

    private fun packet(id: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN).put(id.toByte()).putInt(data.size).put(data).array()

    /** Отправляет данные от ГУ кусками и ждёт OKAY на каждый, как настоящий adbd. */
    private fun writeToClient(remote: Int, local: Int, data: ByteArray, chunk: Int) {
        var position = 0
        while (position < data.size) {
            val count = minOf(chunk, data.size - position)
            send(AdbConnection.A_WRTE, remote, local, data.copyOfRange(position, position + count))
            val ack = read()
            check(ack.command == AdbConnection.A_OKAY && ack.arg1 == remote) { "expected OKAY" }
            position += count
        }
    }

    private fun shell(local: Int) {
        val remote = nextRemoteId++
        send(AdbConnection.A_OKAY, remote, local)
        val stdin = read()
        check(stdin.command == AdbConnection.A_WRTE)
        if (stdin.data.isNotEmpty() && stdin.data[0].toInt() == 4) {
            stdinClosed.set(true)
        }
        send(AdbConnection.A_OKAY, remote, local)
        if (hangShell) {
            Thread.sleep(60_000)
        }
        val stream = ByteArrayOutputStream()
        stream.write(packet(1, STDOUT.toByteArray()))
        stream.write(packet(2, "Exception: unknown permission\n".toByteArray()))
        stream.write(packet(3, byteArrayOf(3)))
        // Куски по 7 байт режут и заголовки пакетов shell_v2, и данные.
        writeToClient(remote, local, stream.toByteArray(), 7)
        send(AdbConnection.A_CLSE, remote, local)
    }

    private fun sync(local: Int) {
        val remote = nextRemoteId++
        send(AdbConnection.A_OKAY, remote, local)
        val pending = ByteArrayOutputStream()
        fun take(count: Int): ByteArray {
            while (pending.size() < count) {
                val message = read()
                check(message.command == AdbConnection.A_WRTE) { "expected WRTE in sync" }
                check(message.data.size <= maxData) { "client payload above device maxdata" }
                pending.write(message.data)
                send(AdbConnection.A_OKAY, remote, local)
            }
            val all = pending.toByteArray()
            pending.reset()
            pending.write(all, count, all.size - count)
            return all.copyOfRange(0, count)
        }
        fun request(): Pair<String, Int> {
            val header = take(8)
            return String(header, 0, 4, Charsets.US_ASCII) to
                ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }

        while (true) {
            val (id, length) = request()
            when (id) {
                "SEND" -> {
                    pushedTarget.set(String(take(length), Charsets.UTF_8))
                    val file = ByteArrayOutputStream()
                    while (true) {
                        val (chunkId, value) = request()
                        if (chunkId == "DATA") {
                            check(value <= 64 * 1024) { "DATA chunk too big" }
                            file.write(take(value))
                        } else {
                            check(chunkId == "DONE")
                            pushedMtime.set(value)
                            break
                        }
                    }
                    pushedData.set(file.toByteArray())
                    val reply = if (failPush != null) {
                        ByteBuffer.allocate(8 + failPush.length).order(ByteOrder.LITTLE_ENDIAN)
                            .put("FAIL".toByteArray()).putInt(failPush.length).put(failPush.toByteArray()).array()
                    } else {
                        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put("OKAY".toByteArray()).putInt(0).array()
                    }
                    writeToClient(remote, local, reply, maxData)
                    if (failPush != null) {
                        send(AdbConnection.A_CLSE, remote, local)
                        return
                    }
                }
                "QUIT" -> {
                    send(AdbConnection.A_CLSE, remote, local)
                    // Клиент может прислать свой CLSE — дочитаем его в основном цикле как лишний.
                    return
                }
                else -> error("unexpected sync request $id")
            }
        }
    }

    companion object {
        const val STDOUT = "Success\nline two with юникод\n"
    }
}
