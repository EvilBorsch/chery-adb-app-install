package local.desaysv.apk.installer.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

class AdbTimeoutException(message: String) : IOException(message)

private class StreamClosedException : IOException("ГУ закрыло поток ADB")

/**
 * Клиент протокола ADB (system/core/adb/protocol.txt) поверх любого [AdbTransport].
 *
 * Операции выполняются строго по одной — так их и вызывает установщик. Отдельный поток только
 * читает сообщения и складывает их в очередь, чтобы ожидание ответа можно было ограничить
 * по времени и чтобы отключение ГУ сразу превращалось в понятную ошибку.
 */
class AdbConnection private constructor(private val transport: AdbTransport) : Closeable {
    private val inbox = LinkedBlockingQueue<Any>()
    private val reader = Thread({ readLoop() }, "adb-reader")
    private var nextLocalId = 1

    @Volatile
    private var closed = false

    @Volatile
    var isAlive = true
        private set

    var maxPayload = MAX_PAYLOAD_V1
        private set
    var features: Set<String> = emptySet()
        private set
    var properties: Map<String, String> = emptyMap()
        private set

    val description: String get() = transport.description

    private fun readLoop() {
        val header = ByteArray(HEADER_SIZE)
        try {
            while (!closed) {
                transport.readFully(header, 0, HEADER_SIZE)
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val command = buffer.int
                val arg0 = buffer.int
                val arg1 = buffer.int
                val length = buffer.int
                buffer.int // checksum: adbd с версией протокола 0x01000001 её не проверяет
                val magic = buffer.int
                if (magic != command.inv()) {
                    throw IOException("Повреждённое сообщение ADB от ГУ")
                }
                if (length < 0 || length > MAX_PAYLOAD * 2) {
                    throw IOException("Слишком большое сообщение ADB от ГУ: $length байт")
                }
                val payload = ByteArray(length)
                if (length > 0) {
                    transport.readFully(payload, 0, length)
                }
                inbox.put(AdbMessage(command, arg0, arg1, payload))
            }
        } catch (error: Throwable) {
            isAlive = false
            inbox.put(error)
        }
    }

    private fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray = EMPTY) {
        if (!isAlive) {
            throw IOException("Соединение с ГУ потеряно")
        }
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(checksum(payload))
        header.putInt(command.inv())
        try {
            transport.write(header.array(), payload)
        } catch (error: IOException) {
            isAlive = false
            throw IOException("Соединение с ГУ потеряно: ${error.message}", error)
        }
    }

    private fun poll(deadline: Long, what: String): AdbMessage {
        val remaining = deadline - System.currentTimeMillis()
        val item = (if (remaining > 0) inbox.poll(remaining, TimeUnit.MILLISECONDS) else inbox.poll())
            ?: throw AdbTimeoutException("ГУ не ответило вовремя: $what")
        if (item is Throwable) {
            // Ошибку оставляем в очереди: все последующие операции тоже должны её увидеть.
            inbox.put(item)
            throw IOException("Соединение с ГУ потеряно: ${item.message}", item)
        }
        return item as AdbMessage
    }

    /** Сообщение не для текущего потока: отвечаем так же, как adb, чтобы adbd не ждал зря. */
    private fun handleStray(message: AdbMessage) {
        when (message.command) {
            A_WRTE -> send(A_CLSE, message.arg1, message.arg0)
            A_CNXN, A_AUTH -> {
                isAlive = false
                throw IOException("ГУ сбросило соединение ADB")
            }
        }
    }

    private fun handshake(key: AdbKey, keyName: String, timeoutMs: Long, authTimeoutMs: Long, onAuthPrompt: () -> Unit) {
        send(A_CNXN, A_VERSION, MAX_PAYLOAD, HOST_BANNER.toByteArray(Charsets.UTF_8))
        var deadline = System.currentTimeMillis() + timeoutMs
        var sentSignature = false
        var sentPublicKey = false
        while (true) {
            val message = poll(
                deadline,
                if (sentPublicKey) "разрешите отладку по USB на экране ГУ" else "подключение ADB",
            )
            when (message.command) {
                A_CNXN -> {
                    maxPayload = minOf(MAX_PAYLOAD, maxOf(MAX_PAYLOAD_V1, message.arg1))
                    parseBanner(String(message.payload, Charsets.UTF_8))
                    return
                }
                A_AUTH -> {
                    if (message.arg0 != AUTH_TOKEN) {
                        continue
                    }
                    if (!sentSignature) {
                        sentSignature = true
                        send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(message.payload))
                    } else if (!sentPublicKey) {
                        // Ключ телефона ГУ ещё не знает: adbd покажет запрос «Разрешить отладку?»
                        sentPublicKey = true
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.adbPublicKey(keyName))
                        deadline = System.currentTimeMillis() + authTimeoutMs
                        onAuthPrompt()
                    }
                }
                A_STLS -> throw IOException("ГУ требует TLS — такой режим ADB не поддерживается")
            }
        }
    }

    private fun parseBanner(banner: String) {
        // device::ro.product.name=...;ro.product.model=...;features=shell_v2,cmd,...
        val body = banner.trimEnd('\u0000').substringAfter("::", "")
        val values = HashMap<String, String>()
        for (part in body.split(';')) {
            val separator = part.indexOf('=')
            if (separator > 0) {
                values[part.substring(0, separator)] = part.substring(separator + 1)
            }
        }
        properties = values
        features = values["features"]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    @Synchronized
    fun open(destination: String, timeoutMs: Long = OPEN_TIMEOUT_MS): AdbStream {
        val localId = nextLocalId++
        send(A_OPEN, localId, 0, (destination + "\u0000").toByteArray(Charsets.UTF_8))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val message = poll(deadline, "открытие сервиса ${destination.substringBefore(':')}")
            if (message.arg1 == localId && message.command == A_OKAY) {
                return AdbStream(localId, message.arg0)
            }
            if (message.arg1 == localId && message.command == A_CLSE) {
                throw IOException("ГУ отклонило сервис ADB: ${destination.substringBefore(':')}")
            }
            handleStray(message)
        }
    }

    /**
     * То же, что `adb shell <command>`: при поддержке shell_v2 stdout и stderr раздельны и есть
     * настоящий код возврата, как у adb на компьютере.
     */
    @Synchronized
    fun shell(command: String, timeoutMs: Long): ShellResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        return if (features.contains("shell_v2")) {
            shellV2(command, deadline)
        } else {
            shellLegacy(command, deadline)
        }
    }

    private fun shellV2(command: String, deadline: Long): ShellResult {
        val stream = open("shell,v2,raw:$command")
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exitCode: Int? = null
        try {
            try {
                // stdin у adb на компьютере — /dev/null, поэтому сразу сообщаем о его конце.
                stream.write(byteArrayOf(SHELL_CLOSE_STDIN, 0, 0, 0, 0), deadline)
            } catch (_: StreamClosedException) {
                // Команда уже успела завершиться — её вывод лежит в очереди потока.
            }
            val pending = ByteArrayOutputStream()
            while (true) {
                val chunk = stream.read(deadline, "выполнение команды на ГУ") ?: break
                pending.write(chunk)
                // Пакеты shell_v2 ([id][длина LE][данные]) могут резаться на границах WRTE.
                val data = pending.toByteArray()
                var offset = 0
                while (data.size - offset >= 5) {
                    val id = data[offset].toInt()
                    val length = ByteBuffer.wrap(data, offset + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (length < 0 || data.size - offset - 5 < length) {
                        break
                    }
                    val start = offset + 5
                    when (id) {
                        SHELL_STDOUT.toInt() -> stdout.write(data, start, length)
                        SHELL_STDERR.toInt() -> stderr.write(data, start, length)
                        SHELL_EXIT.toInt() -> if (length > 0) exitCode = data[start].toInt() and 0xff
                    }
                    offset = start + length
                }
                pending.reset()
                pending.write(data, offset, data.size - offset)
            }
        } finally {
            stream.close()
        }
        return ShellResult(
            exitCode ?: 255,
            stdout.toString(Charsets.UTF_8.name()),
            stderr.toString(Charsets.UTF_8.name()),
        )
    }

    /** Старые adbd без shell_v2: код возврата получаем маркером в конце вывода. */
    private fun shellLegacy(command: String, deadline: Long): ShellResult {
        val stream = open("shell:$command; echo \"$EXIT_MARKER\$?\"")
        val output = ByteArrayOutputStream()
        try {
            while (true) {
                val chunk = stream.read(deadline, "выполнение команды на ГУ") ?: break
                output.write(chunk)
            }
        } finally {
            stream.close()
        }
        val text = output.toString(Charsets.UTF_8.name()).replace("\r\n", "\n")
        val markerAt = text.lastIndexOf(EXIT_MARKER)
        if (markerAt < 0) {
            return ShellResult(255, text, "")
        }
        val code = text.substring(markerAt + EXIT_MARKER.length).trim().toIntOrNull() ?: 255
        return ShellResult(code, text.substring(0, markerAt), "")
    }

    /** То же, что `adb push`: протокол sync, файл пишется на ГУ с заданным режимом доступа. */
    @Synchronized
    fun push(input: InputStream, remotePath: String, mode: Int, mtimeSeconds: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val stream = open("sync:")
        try {
            val writer = SyncWriter(stream, deadline)
            try {
                writer.request("SEND", "$remotePath,$mode".toByteArray(Charsets.UTF_8))
                val buffer = ByteArray(SYNC_DATA_MAX)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        break
                    }
                    if (count > 0) {
                        writer.request("DATA", buffer, count)
                    }
                }
                writer.requestWithValue("DONE", mtimeSeconds)
                writer.flush()
            } catch (closed: StreamClosedException) {
                // adbd обрывает поток после FAIL: достаём его текст, иначе причина потеряется.
                throw IOException(readSyncFailure(stream, deadline) ?: "ГУ прервало копирование файла", closed)
            }

            val reply = SyncReader(stream, deadline)
            val id = String(reply.read(4), Charsets.US_ASCII)
            val length = ByteBuffer.wrap(reply.read(4)).order(ByteOrder.LITTLE_ENDIAN).int
            when (id) {
                "OKAY" -> Unit
                "FAIL" -> throw IOException(
                    "ГУ не приняло файл: " + String(reply.read(length), Charsets.UTF_8),
                )
                else -> throw IOException("Неожиданный ответ ГУ при копировании: $id")
            }
            try {
                writer.requestWithValue("QUIT", 0)
                writer.flush()
            } catch (_: IOException) {
            }
        } finally {
            stream.close()
        }
    }

    private fun readSyncFailure(stream: AdbStream, deadline: Long): String? {
        return try {
            val reader = SyncReader(stream, deadline)
            if (String(reader.read(4), Charsets.US_ASCII) != "FAIL") {
                return null
            }
            val length = ByteBuffer.wrap(reader.read(4)).order(ByteOrder.LITTLE_ENDIAN).int
            "ГУ не приняло файл: " + String(reader.read(length), Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        isAlive = false
        transport.close()
        reader.interrupt()
    }

    inner class AdbStream internal constructor(private val localId: Int, private val remoteId: Int) {
        private val received = ArrayDeque<ByteArray>()
        private var remoteClosed = false
        private var localClosed = false

        fun write(data: ByteArray, deadline: Long) = write(data, data.size, deadline)

        fun write(data: ByteArray, length: Int, deadline: Long) {
            var position = 0
            while (position < length) {
                if (remoteClosed) {
                    throw StreamClosedException()
                }
                val count = minOf(maxPayload, length - position)
                send(A_WRTE, localId, remoteId, data.copyOfRange(position, position + count))
                awaitOkay(deadline)
                position += count
            }
        }

        private fun awaitOkay(deadline: Long) {
            while (true) {
                val message = poll(deadline, "подтверждение данных от ГУ")
                if (message.arg1 != localId) {
                    handleStray(message)
                    continue
                }
                when (message.command) {
                    A_OKAY -> return
                    A_WRTE -> {
                        received.add(message.payload)
                        send(A_OKAY, localId, remoteId)
                    }
                    A_CLSE -> {
                        remoteClosed = true
                        throw StreamClosedException()
                    }
                }
            }
        }

        /** Следующий блок данных от ГУ или null, когда ГУ закрыло поток. */
        fun read(deadline: Long, what: String): ByteArray? {
            received.pollFirst()?.let { return it }
            if (remoteClosed) {
                return null
            }
            while (true) {
                val message = poll(deadline, what)
                if (message.arg1 != localId) {
                    handleStray(message)
                    continue
                }
                when (message.command) {
                    A_WRTE -> {
                        send(A_OKAY, localId, remoteId)
                        return message.payload
                    }
                    A_CLSE -> {
                        remoteClosed = true
                        return null
                    }
                }
            }
        }

        fun close() {
            if (localClosed || remoteClosed) {
                localClosed = true
                return
            }
            localClosed = true
            try {
                send(A_CLSE, localId, remoteId)
            } catch (_: IOException) {
            }
        }
    }

    /** Склеивает запросы sync в блоки по maxPayload: так adb и шлёт их по проводу. */
    private inner class SyncWriter(private val stream: AdbStream, private val deadline: Long) {
        private val buffer = ByteArray(maxPayload)
        private var size = 0

        fun request(id: String, data: ByteArray, length: Int = data.size) {
            append(header(id, length), 8)
            append(data, length)
        }

        fun requestWithValue(id: String, value: Int) = append(header(id, value), 8)

        private fun header(id: String, value: Int): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put(id.toByteArray(Charsets.US_ASCII))
                .putInt(value)
                .array()

        private fun append(data: ByteArray, length: Int) {
            var position = 0
            while (position < length) {
                val count = minOf(buffer.size - size, length - position)
                System.arraycopy(data, position, buffer, size, count)
                size += count
                position += count
                if (size == buffer.size) {
                    flush()
                }
            }
        }

        fun flush() {
            if (size > 0) {
                stream.write(buffer, size, deadline)
                size = 0
            }
        }
    }

    private class SyncReader(private val stream: AdbStream, private val deadline: Long) {
        private var current = ByteArray(0)
        private var offset = 0

        fun read(length: Int): ByteArray {
            val result = ByteArray(length)
            var got = 0
            while (got < length) {
                if (offset == current.size) {
                    current = stream.read(deadline, "ответ ГУ при копировании файла")
                        ?: throw IOException("ГУ закрыло поток копирования")
                    offset = 0
                    continue
                }
                val count = minOf(length - got, current.size - offset)
                System.arraycopy(current, offset, result, got, count)
                offset += count
                got += count
            }
            return result
        }
    }

    companion object {
        private const val HEADER_SIZE = 24
        private val EMPTY = ByteArray(0)

        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_AUTH = 0x48545541
        const val A_STLS = 0x534c5453

        private const val A_VERSION = 0x01000001
        private const val MAX_PAYLOAD_V1 = 4 * 1024
        private const val MAX_PAYLOAD = 1024 * 1024
        private const val SYNC_DATA_MAX = 64 * 1024

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        private const val SHELL_STDOUT: Byte = 1
        private const val SHELL_STDERR: Byte = 2
        private const val SHELL_EXIT: Byte = 3
        private const val SHELL_CLOSE_STDIN: Byte = 4

        private const val OPEN_TIMEOUT_MS = 30_000L
        private const val EXIT_MARKER = "__DESAYSV_EXIT__="

        // Набор возможностей как у adb на компьютере, без delayed_ack: он меняет управление
        // потоком, а нам нужен классический режим «WRTE → OKAY».
        private const val HOST_BANNER =
            "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app"

        private fun checksum(payload: ByteArray): Int {
            var sum = 0
            for (byte in payload) {
                sum += byte.toInt() and 0xff
            }
            return sum
        }

        /**
         * Подключается и проходит авторизацию. [onAuthPrompt] вызывается, когда ГУ показало
         * запрос «Разрешить отладку?» — после него ждём ответа пользователя [authTimeoutMs].
         */
        fun connect(
            transport: AdbTransport,
            key: AdbKey,
            keyName: String,
            timeoutMs: Long,
            authTimeoutMs: Long,
            onAuthPrompt: () -> Unit = {},
        ): AdbConnection {
            val connection = AdbConnection(transport)
            connection.reader.isDaemon = true
            connection.reader.start()
            try {
                connection.handshake(key, keyName, timeoutMs, authTimeoutMs, onAuthPrompt)
            } catch (error: Throwable) {
                connection.close()
                throw error
            }
            return connection
        }
    }
}
