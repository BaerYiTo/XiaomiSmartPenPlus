package xiaomismartpen.plus;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.SystemProperties;
import android.provider.Settings;
import java.util.Objects;

/** Calls the real bridge; Android side effects are captured by focused doubles. */
public final class SystemPenBridgeTests {
    static int checks;

    static void eq(Object expected, Object actual, String message) {
        checks++;
        if (!Objects.equals(expected, actual))
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
    }

    static void setup(String testCase) {
        Build.FINGERPRINT = "generic/device/product:17/release-keys";
        Build.DISPLAY = "release";
        Build.MANUFACTURER = "Xiaomi";
        Build.BRAND = "Xiaomi";
        SystemProperties.reset();
        if ("color".equals(testCase)) SystemProperties.values.put("ro.build.version.coloros", "17");
        if ("hyper".equals(testCase)) SystemProperties.values.put("ro.mi.os.version.name", "OS3.0");
        Handler.reset();
        Settings.Global.reset();
        ContentResolver.reset();
        NotificationManager.reset();
        Settings.Global.values.put("ipe_pencil_battery_level", "original");
    }

    public static void main(String[] args) {
        String testCase = args[0];
        setup(testCase);
        Context context = new Context();
        SystemPenBridge.configure(context, PenConfig.defaults());
        Handler.advance(0);
        eq("10000", Settings.Global.values.get("focuspen_module_heartbeat"), "all ROMs publish immediate heartbeat");
        Handler.advance(15000);
        eq("25000", Settings.Global.values.get("focuspen_module_heartbeat"), "all ROMs keep heartbeat alive");
        SystemPenBridge.configure(context, PenConfig.defaults());
        Handler.advance(0);
        eq(1, Handler.pendingCount(), "repeat configuration keeps one heartbeat chain");
        if ("color".equals(testCase)) {
            eq(1, ContentResolver.registrations, "ColorOS registers one hardware observer");
            eq("2", Settings.Global.values.get("ipe_pencil_connect_state"), "ColorOS writes connected state");
            eq("1", Settings.Global.values.get("ipe_pencil_present"), "ColorOS writes presence");
            eq("88", Settings.Global.values.get("ipe_pencil_battery_level"), "ColorOS writes battery");
            eq("11:22:33:44:55:66", Settings.Global.values.get("ipe_pencil_mac_addr"), "ColorOS writes MAC");
            eq(true, NotificationManager.notifications.contains(9270), "ColorOS posts battery notification");
            eq(true, NotificationManager.channels.contains("focuspen_battery"), "ColorOS creates battery channel");
            eq(true, HardwareState.reads > 0, "ColorOS reads hardware");
        } else {
            eq(0, ContentResolver.registrations, "non-ColorOS skips hardware observer");
            eq("original", Settings.Global.values.get("ipe_pencil_battery_level"), "non-ColorOS preserves existing Oplus setting");
            eq(false, Settings.Global.writtenKeys.stream().anyMatch(key -> key.startsWith("ipe_pencil_")), "non-ColorOS never writes Oplus settings");
            eq(0, NotificationManager.notifications.size(), "non-ColorOS skips ColorOS notifications");
            eq(0, NotificationManager.channels.size(), "non-ColorOS skips ColorOS channels");
            eq(0, HardwareState.reads, "non-ColorOS skips ColorOS hardware reads");
        }
        System.out.println("PASS " + checks + " SystemPenBridge assertions (" + testCase + ")");
    }
}
