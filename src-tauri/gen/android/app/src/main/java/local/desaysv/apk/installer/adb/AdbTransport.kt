package local.desaysv.apk.installer.adb

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Канал до adbd. Одно сообщение ADB = заголовок 24 байта + payload; транспорт сам решает,
 * как передать их по проводу (USB bulk или TCP).
 *
 * write() вызывается из потока операций, readFully() — из отдельного потока чтения,
 * поэтому реализации должны допускать их одновременную работу.
 */
interface AdbTransport : Closeable {
    val description: String

    @Throws(IOException::class)
    fun write(header: ByteArray, payload: ByteArray)

    @Throws(IOException::class)
    fun readFully(buffer: ByteArray, offset: Int, length: Int)
}

/**
 * ADB по TCP. На ГУ не используется (там USB), нужен для проверки протокола на эмуляторе,
 * где adbd слушает 127.0.0.1:5555 после `adb tcpip 5555`.
 */
class TcpAdbTransport(host: String, port: Int, connectTimeoutMs: Int = 3000) : AdbTransport {
    private val socket = Socket()
    private val input: InputStream
    private val output: OutputStream
    private val writeLock = Any()

    override val description = "TCP $host:$port"

    init {
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    override fun write(header: ByteArray, payload: ByteArray) {
        synchronized(writeLock) {
            output.write(header)
            if (payload.isNotEmpty()) {
                output.write(payload)
            }
            output.flush()
        }
    }

    override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var got = 0
        while (got < length) {
            val count = input.read(buffer, offset + got, length - got)
            if (count < 0) {
                throw IOException("Соединение закрыто")
            }
            got += count
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}
