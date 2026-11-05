package local.desaysv.apk.installer.adb

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Проверка против настоящего adbd (эмулятор или ГУ с ADB по сети). Запускается только когда
 * задан ADB_IT_TARGET=host:port — например ADB-порт эмулятора, не занятый adb-сервером.
 * ADB_IT_APK — тестовый APK для установки, ADB_IT_HELPER — src-tauri/resources/localinstall.apk.
 *
 * Повторяет шаги установщика: push APK и helper, установка через PackageInstaller.Session,
 * выдача appops — те же команды, что шлёт общий Rust-код.
 */
class RealAdbdIntegrationTest {
    @Test
    fun installerFlowAgainstRealAdbd() {
        val target = System.getenv("ADB_IT_TARGET")
        assumeTrue("ADB_IT_TARGET не задан", !target.isNullOrBlank())
        val apk = File(System.getenv("ADB_IT_APK") ?: "")
        val helper = File(System.getenv("ADB_IT_HELPER") ?: "")
        assumeTrue(apk.isFile && helper.isFile)

        val key = AdbKey.loadOrCreate(Files.createTempDirectory("adbkey").toFile())
        val transport = TcpAdbTransport(target!!.substringBeforeLast(':'), target.substringAfterLast(':').toInt())
        AdbConnection.connect(transport, key, "it@test", 15_000, 120_000) {
            println("Разрешите отладку на экране устройства")
        }.use { adb ->
            println("features=${adb.features} maxPayload=${adb.maxPayload} props=${adb.properties}")

            // Код возврата и раздельные потоки, как у adb shell на компьютере.
            val exit = adb.shell("echo out; echo err 1>&2; exit 7", 30_000)
            assertEquals(7, exit.exitCode)
            assertEquals("out\n", exit.stdout)
            assertEquals("err\n", exit.stderr)

            // Склейка аргументов через пробел без экранирования — как adb shell a b c.
            assertEquals("11", adb.shell("getprop ro.build.version.release", 30_000).stdout.trim())

            // Большой файл: проверяем целостность после sync push.
            val big = ByteArray(24 * 1024 * 1024 + 123).also { Random(7).nextBytes(it) }
            val started = System.currentTimeMillis()
            adb.push(ByteArrayInputStream(big), "/data/local/tmp/desaysv-it-big.bin", 0x81a4, 0, 600_000)
            println("push 24 MB: ${System.currentTimeMillis() - started} ms")
            val md5 = MessageDigest.getInstance("MD5").digest(big).joinToString("") { "%02x".format(it) }
            val remoteMd5 = adb.shell("md5sum /data/local/tmp/desaysv-it-big.bin", 60_000).stdout.substringBefore(' ')
            assertEquals(md5, remoteMd5)
            adb.shell("rm -f /data/local/tmp/desaysv-it-big.bin", 30_000)

            val pkg = "test.desaysv.probe"
            adb.shell("pm uninstall $pkg", 60_000)

            val remoteApk = "/data/local/tmp/desaysv-install-target.apk"
            val remoteHelper = "/data/local/tmp/desaysv-localinstall.apk"
            apk.inputStream().use { adb.push(it, remoteApk, 0x81a4, 0, 600_000) }
            helper.inputStream().use { adb.push(it, remoteHelper, 0x81a4, 0, 60_000) }
            val install = adb.shell(
                "CLASSPATH=$remoteHelper /system/bin/app_process /system/bin LocalInstall $remoteApk 120",
                300_000,
            )
            println(install.stdout)
            assertEquals(install.stdout + install.stderr, 0, install.exitCode)
            assertTrue(install.stdout.contains("LOCALINSTALL_STATUS=0"))
            assertTrue(adb.shell("pm list packages $pkg", 30_000).stdout.contains("package:$pkg"))

            // Та же выдача прав, что делает установщик: runtime + appops (VPN без диалога).
            for (permission in listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
            )) {
                assertEquals(0, adb.shell("pm grant $pkg $permission", 30_000).exitCode)
            }
            for (op in listOf("ACTIVATE_VPN", "SYSTEM_ALERT_WINDOW", "MANAGE_EXTERNAL_STORAGE", "REQUEST_INSTALL_PACKAGES")) {
                assertEquals(op, 0, adb.shell("appops set $pkg $op allow", 30_000).exitCode)
            }
            val appops = adb.shell("appops get $pkg", 30_000).stdout
            println(appops)
            assertTrue(appops.contains("ACTIVATE_VPN: allow"))
            assertTrue(appops.contains("SYSTEM_ALERT_WINDOW: allow"))
            val granted = adb.shell("dumpsys package $pkg", 60_000).stdout
            assertTrue(granted.contains("android.permission.CAMERA: granted=true"))

            // Неудачная команда — ненулевой код, но соединение живо.
            assertTrue(adb.shell("pm grant $pkg android.permission.NOT_A_PERMISSION", 30_000).exitCode != 0)
            adb.shell("rm -f $remoteApk $remoteHelper", 30_000)
            assertTrue(adb.isAlive)
        }
    }
}
