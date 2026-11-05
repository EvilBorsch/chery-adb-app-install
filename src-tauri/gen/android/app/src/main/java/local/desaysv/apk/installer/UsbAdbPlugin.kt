package local.desaysv.apk.installer

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.WebView
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.tauri.annotation.ActivityCallback
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import local.desaysv.apk.installer.adb.AdbConnection
import local.desaysv.apk.installer.adb.AdbKey
import local.desaysv.apk.installer.adb.AdbTransport
import local.desaysv.apk.installer.adb.TcpAdbTransport
import local.desaysv.apk.installer.adb.UsbAdbTransport

@InvokeArg
class ShellArgs {
    lateinit var command: String
    var timeoutMs: Long = 10 * 60 * 1000L
}

@InvokeArg
class PushArgs {
    lateinit var localPath: String
    lateinit var remotePath: String
    var mode: Int = 0x81a4 // 0100644: обычный файл rw-r--r--, как у adb push
    var timeoutMs: Long = 30 * 60 * 1000L
}

/**
 * Мост Rust → Android: вся логика установки и выдачи прав живёт в Rust и общая с ПК-версией,
 * а здесь только «провод» до ГУ — ADB по USB, где телефон выступает хостом (OTG).
 *
 * Команды Tauri вызываются в главном потоке, а операции ADB длятся до минут, поэтому всё
 * выполняется в одном фоновом потоке — строго по очереди, как и у adb на компьютере.
 */
@TauriPlugin
class UsbAdbPlugin(private val activity: Activity) : Plugin(activity) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "usb-adb").apply { isDaemon = true }
    }
    private val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val key: AdbKey by lazy { AdbKey.loadOrCreate(File(activity.filesDir, "adb")) }

    // Доступ только из worker.
    private var session: AdbConnection? = null
    private var sessionDevice: String? = null

    private fun runOnWorker(invoke: Invoke, block: () -> JSObject?) {
        worker.execute {
            try {
                invoke.resolve(block())
            } catch (error: Throwable) {
                Log.w(TAG, "USB ADB command failed", error)
                invoke.reject(error.message ?: error.toString())
            }
        }
    }

    @Command
    fun connect(invoke: Invoke) {
        runOnWorker(invoke) { connectInternal() }
    }

    @Command
    fun shell(invoke: Invoke) {
        val args = invoke.parseArgs(ShellArgs::class.java)
        runOnWorker(invoke) {
            val result = withSession { it.shell(args.command, args.timeoutMs) }
            JSObject().apply {
                put("exitCode", result.exitCode)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
            }
        }
    }

    @Command
    fun push(invoke: Invoke) {
        val args = invoke.parseArgs(PushArgs::class.java)
        runOnWorker(invoke) {
            val file = File(args.localPath)
            if (!file.isFile) {
                throw IOException("Файл не найден: ${args.localPath}")
            }
            withSession { connection ->
                FileInputStream(file).use { input ->
                    connection.push(
                        input,
                        args.remotePath,
                        args.mode,
                        (file.lastModified() / 1000).toInt(),
                        args.timeoutMs,
                    )
                }
            }
            JSObject()
        }
    }

    /** Ошибка посреди операции оставляет поток ADB в неизвестном состоянии — переподключаемся. */
    private fun <T> withSession(block: (AdbConnection) -> T): T {
        val connection = session?.takeIf { it.isAlive }
            ?: throw IOException("ГУ не подключено. Нажмите «Проверить ГУ».")
        try {
            return block(connection)
        } catch (error: Throwable) {
            closeSession()
            throw error
        }
    }

    private fun closeSession() {
        session?.close()
        session = null
        sessionDevice = null
    }

    private fun connectInternal(): JSObject {
        val device = findAdbDevice()
        val current = session
        // Живое соединение с тем же устройством переиспользуем: каждая операция установщика
        // начинается с проверки ГУ, и переподключение на каждый шаг было бы долгим.
        if (current != null && current.isAlive && device?.deviceName == sessionDevice) {
            return describe(current, device)
        }
        closeSession()

        val transport: AdbTransport = when {
            device != null -> {
                if (!usbManager.hasPermission(device) && !requestUsbPermission(device)) {
                    return notConnected("Нет доступа к USB-устройству ГУ. Разрешите доступ в окне Android и нажмите «Проверить ГУ» ещё раз.")
                }
                UsbAdbTransport.open(usbManager, device)
            }
            isDebuggable() -> debugTcpTransport()
                ?: return notConnected(NOT_FOUND_MESSAGE)
            else -> return notConnected(NOT_FOUND_MESSAGE)
        }

        val connection = try {
            AdbConnection.connect(
                transport,
                key,
                "desaysv-installer@${Build.MANUFACTURER}-${Build.MODEL}".replace(' ', '_'),
                HANDSHAKE_TIMEOUT_MS,
                AUTH_TIMEOUT_MS,
            )
        } catch (error: Throwable) {
            transport.close()
            return notConnected(
                "Не удалось подключиться к ГУ по ADB: ${error.message}. " +
                    "Если на экране ГУ был запрос отладки по USB — нажмите «Разрешить» и проверьте ГУ ещё раз.",
            )
        }
        session = connection
        sessionDevice = device?.deviceName
        return describe(connection, device)
    }

    private fun describe(connection: AdbConnection, device: UsbDevice?): JSObject {
        val serial = try {
            device?.serialNumber
        } catch (_: SecurityException) {
            null
        }
        return JSObject().apply {
            put("connected", true)
            put("transport", connection.description)
            put("serial", serial ?: connection.properties["ro.serialno"] ?: device?.deviceName ?: connection.description)
            put("model", connection.properties["ro.product.model"])
            put("product", connection.properties["ro.product.name"])
        }
    }

    private fun notConnected(message: String) = JSObject().apply {
        put("connected", false)
        put("message", message)
    }

    private fun findAdbDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { UsbAdbTransport.findAdbInterface(it) != null }

    private fun requestUsbPermission(device: UsbDevice): Boolean {
        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            // Android 12+ требует MUTABLE (система дописывает результат в intent), а Android 14
            // разрешает изменяемый PendingIntent только с явным адресатом — отсюда setPackage.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(activity.packageName)
            val pending = PendingIntent.getBroadcast(activity, 0, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
            usbManager.requestPermission(device, pending)
            latch.await(USB_PERMISSION_TIMEOUT_S, TimeUnit.SECONDS)
        } finally {
            activity.unregisterReceiver(receiver)
        }
        return granted || usbManager.hasPermission(device)
    }

    private fun isDebuggable() = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Только для отладочной сборки: проверка протокола на эмуляторах. Адрес adbd берётся из
     * files/debug_adb_target (host:port), например ADB-порт второго эмулятора — 10.0.2.2:5591.
     */
    private fun debugTcpTransport(): AdbTransport? = try {
        val target = File(activity.filesDir, "debug_adb_target").takeIf { it.isFile }?.readText()?.trim()
            ?: "127.0.0.1:5555"
        TcpAdbTransport(target.substringBeforeLast(':'), target.substringAfterLast(':').toInt())
    } catch (_: Exception) {
        null
    }

    @Command
    fun pickApk(invoke: Invoke) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Часть файловых провайдеров отдаёт APK как octet-stream или zip.
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream",
                    "application/zip",
                    "application/x-zip-compressed",
                ),
            )
        }
        startActivityForResult(invoke, intent, "apkPicked")
    }

    @ActivityCallback
    fun apkPicked(invoke: Invoke, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            invoke.resolve(JSObject().apply { put("cancelled", true) })
            return
        }
        // Копию делаем в отдельном потоке: большой APK (карты) копируется секунды.
        Thread({
            try {
                invoke.resolve(copyPickedApk(uri))
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to read picked APK", error)
                invoke.reject(error.message ?: error.toString())
            }
        }, "apk-copy").start()
    }

    /**
     * APK копируется во внутренний кэш: дальше с ним работает общий Rust-код как с обычным
     * файлом — читает имя пакета из манифеста и отправляет на ГУ, как ПК-версия.
     */
    private fun copyPickedApk(uri: Uri): JSObject {
        val resolver = activity.contentResolver
        var name = "selected.apk"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                name = cursor.getString(0)
            }
        }

        val directory = File(activity.cacheDir, PICKED_DIR)
        directory.deleteRecursively()
        directory.mkdirs()
        var fileName = name.replace(Regex("[^\\p{L}\\p{N}._ ()-]"), "_").trim().ifEmpty { "selected" }
        if (!fileName.endsWith(".apk", ignoreCase = true)) {
            fileName += ".apk"
        }
        val target = File(directory, fileName)
        val input = resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть выбранный файл")
        input.use { source -> target.outputStream().use { source.copyTo(it, 256 * 1024) } }

        val magic = ByteArray(4)
        val read = FileInputStream(target).use { it.read(magic) }
        if (read != 4 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
            target.delete()
            throw IOException("Выбранный файл не похож на APK: $name")
        }
        return JSObject().apply {
            put("cancelled", false)
            put("path", target.absolutePath)
            put("name", name)
            put("size", target.length())
        }
    }

    override fun load(webView: WebView) {
        // Копия APK из прошлого запуска не нужна: выбор файла в интерфейсе уже сброшен,
        // а карты и лаунчер весят сотни мегабайт.
        Thread({ File(activity.cacheDir, PICKED_DIR).deleteRecursively() }, "apk-cache-cleanup").start()
    }

    override fun onDestroy(activity: AppCompatActivity) {
        worker.execute { closeSession() }
    }

    companion object {
        private const val TAG = "UsbAdbPlugin"
        private const val PICKED_DIR = "picked-apk"
        private const val ACTION_USB_PERMISSION = "local.desaysv.apk.installer.USB_PERMISSION"
        private const val USB_PERMISSION_TIMEOUT_S = 120L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val AUTH_TIMEOUT_MS = 120_000L
        private const val NOT_FOUND_MESSAGE =
            "ГУ не найдено по USB. Подключите ГУ к телефону кабелем через OTG-переходник " +
                "(так же, как к компьютеру) и нажмите «Проверить ГУ»."
    }
}
