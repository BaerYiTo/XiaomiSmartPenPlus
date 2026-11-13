package xiaomismartpen.plus;

import android.os.Build;

import java.lang.reflect.Method;
import java.util.Locale;

/** 按 ROM 选择笔键策略和系统集成，界面统一使用 Miuix。 */
final class RomProfile {
    enum Family {
        HYPEROS,
        COLOROS,
        UNKNOWN
    }

    private static final Family DETECTED = detectInternal();

    private RomProfile() { }

    static Family detect() {
        return DETECTED;
    }

    private static Family detectInternal() {
        String colorOsVersion = property("ro.build.version.coloros");
        String oplusVersion = property("ro.build.version.oplus");
        String oplusRomVersion = property("ro.build.version.oplusrom");
        String oppoRomVersion = property("ro.build.version.opporom");
        String fingerprint = lower(Build.FINGERPRINT);
        String display = lower(Build.DISPLAY);
        String manufacturer = lower(Build.MANUFACTURER);
        String brand = lower(Build.BRAND);

        // ColorOS/OPlus wins when a port leaves Xiaomi product properties behind.
        if (!colorOsVersion.isEmpty() || !oplusVersion.isEmpty() || !oplusRomVersion.isEmpty()
                || !oppoRomVersion.isEmpty() || fingerprint.contains("coloros")
                || display.contains("coloros") || fingerprint.startsWith("oneplus/")
                || fingerprint.startsWith("oppo/") || isOplusBrand(manufacturer)
                || isOplusBrand(brand)) {
            return Family.COLOROS;
        }

        if (!property("ro.mi.os.version.name").isEmpty()
                || !property("ro.mi.os.version.code").isEmpty()
                || !property("ro.miui.ui.version.name").isEmpty()
                || !property("ro.miui.ui.version.code").isEmpty()
                || fingerprint.contains("hyperos") || display.contains("hyperos")
                || fingerprint.contains("miui") || display.contains("miui")) {
            return Family.HYPEROS;
        }

        return Family.UNKNOWN;
    }

    private static boolean isOplusBrand(String name) {
        return "oppo".equals(name) || "oneplus".equals(name) || "oplus".equals(name);
    }

    private static String property(String name) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getMethod("get", String.class);
            Object value = get.invoke(null, name);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return "";
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
