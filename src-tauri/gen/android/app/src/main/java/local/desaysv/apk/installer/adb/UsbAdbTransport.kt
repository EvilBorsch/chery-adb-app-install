package local.desaysv.apk.installer.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.io.IOException
import java.nio.ByteBuffer

/**
 * ADB по USB, где телефон — хост (OTG), а ГУ — устройство, как при подключении к компьютеру.
 *
 * Повторяет поведение adb на компьютере: заголовок и payload уходят отдельными передачами,
 * а если длина payload кратна размеру пакета, следом идёт пакет нулевой длины — иначе adbd
 * не поймёт, где закончилось сообщение.
 */
class UsbAdbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    override val description: String,
) : AdbTransport {
    private val writeLock = Any()
    private val readLock = Any()

    // Чтение идёт через UsbRequest, а не bulkTransfer: заблокированный bulkTransfer нельзя
    // прервать при закрытии, и его «висящий» запрос съел бы данные следующего подключения.
    // UsbRequest.cancel() снимает ожидание, а данные между ожиданиями не теряются.
    private val readRequest = UsbRequest()
    private val readBuffer = ByteBuffer.allocateDirect(TRANSFER_CHUNK)

    @Volatile
    private var closed = false

    init {
        if (!readRequest.initialize(connection, inEndpoint)) {
            throw IOException("Не удалось подготовить чтение USB")
        }
    }

    override fun write(header: ByteArray, payload: ByteArray) {
        synchronized(writeLock) {
            bulkOut(header, header.size)
            if (payload.isNotEmpty()) {
                bulkOut(payload, payload.size)
                if (payload.size % outEndpoint.maxPacketSize == 0) {
                    val sent = connection.bulkTransfer(outEndpoint, ByteArray(0), 0, 0, WRITE_TIMEOUT_MS)
                    if (sent < 0) {
                        throw IOException("Не удалось отправить данные на ГУ по USB")
                    }
                }
            }
        }
    }

    private fun bulkOut(data: ByteArray, length: Int) {
        var position = 0
        while (position < length) {
            ensureOpen()
            // До Android 9 одна передача ограничена 16 КБ.
            val chunk = minOf(TRANSFER_CHUNK, length - position)
            val sent = connection.bulkTransfer(outEndpoint, data, position, chunk, WRITE_TIMEOUT_MS)
            if (sent <= 0) {
                throw IOException("Не удалось отправить данные на ГУ по USB")
            }
            position += sent
        }
    }

    override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        synchronized(readLock) {
            var got = 0
            var emptyReads = 0
            while (got < length) {
                ensureOpen()
                val chunk = minOf(TRANSFER_CHUNK, length - got)
                readBuffer.clear()
                readBuffer.limit(chunk)
                if (!readRequest.queue(readBuffer)) {
                    throw IOException("Не удалось начать чтение USB")
                }
                val completed = connection.requestWait()
                ensureOpen()
                if (completed == null) {
                    throw IOException("ГУ отключено от USB")
                }
                val count = readBuffer.position()
                if (count == 0) {
                    // Пакет нулевой длины после payload кратного размеру пакета — это норма,
                    // но бесконечный поток пустых ответов означает сломанное соединение.
                    if (++emptyReads > MAX_EMPTY_READS) {
                        throw IOException("ГУ перестало отвечать по USB")
                    }
                    continue
                }
                emptyReads = 0
                readBuffer.flip()
                readBuffer.get(buffer, offset + got, count)
                got += count
            }
        }
    }

    private fun ensureOpen() {
        if (closed) {
            throw IOException("USB-соединение закрыто")
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        try {
            readRequest.cancel()
        } catch (_: Throwable) {
        }
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Throwable) {
        }
        try {
            connection.close()
        } catch (_: Throwable) {
        }
        // close() у UsbRequest вызываем после закрытия соединения: поток чтения к этому
        // моменту уже вышел из requestWait() из-за cancel().
        synchronized(readLock) {
            try {
                readRequest.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TRANSFER_CHUNK = 16 * 1024
        private const val WRITE_TIMEOUT_MS = 15_000
        private const val MAX_EMPTY_READS = 64

        // Интерфейс ADB описан в adb/client/usb_*.cpp: vendor class, subclass 0x42, protocol 1.
        private const val ADB_CLASS = 0xff
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01

        fun findAdbInterface(device: UsbDevice): UsbInterface? {
            for (index in 0 until device.interfaceCount) {
                val candidate = device.getInterface(index)
                if (candidate.interfaceClass == ADB_CLASS &&
                    candidate.interfaceSubclass == ADB_SUBCLASS &&
                    candidate.interfaceProtocol == ADB_PROTOCOL &&
                    bulkEndpoints(candidate) != null
                ) {
                    return candidate
                }
            }
            return null
        }

        private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (index in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue
                }
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    input = input ?: endpoint
                } else {
                    output = output ?: endpoint
                }
            }
            return if (input != null && output != null) input to output else null
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbAdbTransport {
            val usbInterface = findAdbInterface(device)
                ?: throw IOException("У USB-устройства нет интерфейса ADB")
            val (input, output) = bulkEndpoints(usbInterface)!!
            val connection = manager.openDevice(device)
                ?: throw IOException("Android не дал открыть USB-устройство")
            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                throw IOException("Не удалось захватить интерфейс ADB на USB")
            }
            val name = listOfNotNull(device.manufacturerName, device.productName)
                .joinToString(" ")
                .ifBlank { device.deviceName }
            return try {
                UsbAdbTransport(connection, usbInterface, input, output, "USB: $name")
            } catch (error: Throwable) {
                connection.releaseInterface(usbInterface)
                connection.close()
                throw error
            }
        }
    }
}
