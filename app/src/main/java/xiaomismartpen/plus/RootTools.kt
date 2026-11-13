package xiaomismartpen.plus

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

internal object RootTools {
    fun run(command: String): String {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> synchronized(output) { if (output.length < 40000) output.appendLine(line) } }
        } }.apply { start() }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("Root command timed out")
        }
        reader.join(1500)
        val text = synchronized(output) { output.toString().trim() }
        check(process.exitValue() == 0) { text.ifBlank { "Root command failed (${process.exitValue()})" } }
        return text
    }

    fun install(context: Context): String {
        val bin = File(context.codeCacheDir, "penstatusd")
        context.assets.open("penstatusd").use { input -> bin.outputStream().use { input.copyTo(it) } }
        val boot = File(context.codeCacheDir, "focuspen-boot.sh")
        context.assets.open("focuspen-boot.sh").use { input -> boot.outputStream().use { input.copyTo(it) } }
        return run("""
            set -e
            mkdir -p /data/adb/focuspen /data/adb/service.d
            if [ -f /data/adb/service.d/99-penkeyd.sh ]; then
                cp /data/adb/service.d/99-penkeyd.sh /data/adb/focuspen/legacy-99-penkeyd.sh
                rm /data/adb/service.d/99-penkeyd.sh
            fi
            stop xiaomipen-buttons
            stop xiaomipen
            pkill -x penkeyd || true
            pkill -x penstatusd || true
            cp '${bin.absolutePath}' /data/adb/focuspen/penstatusd.new
            chmod 755 /data/adb/focuspen/penstatusd.new
            mv -f /data/adb/focuspen/penstatusd.new /data/adb/focuspen/penstatusd
            cp '${boot.absolutePath}' /data/adb/service.d/98-focuspen.sh
            chmod 755 /data/adb/focuspen/penstatusd /data/adb/service.d/98-focuspen.sh
            nohup /data/adb/focuspen/penstatusd >>/data/adb/focuspen/hardware.log 2>&1 &
            sleep 1
            pidof penstatusd
        """.trimIndent())
    }

    fun openLsposed(): String = run("am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android")
    fun diagnostics(): String = run("""
        id
        pidof penstatusd || true
        pidof penkeyd || true
        settings get global focuspen_hardware
        settings get global focuspen_module_heartbeat
        settings get global focuspen_ipe_heartbeat
        logcat -d -s FocusPenLite:I | tail -90
    """.trimIndent())
}
