package xiaomismartpen.plus;

import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import com.miui.server.input.util.ShortCutActionsUtils;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** Exercise production screenshot routing without invoking either platform service. */
public final class ActionRoutingTests {
    private static int checks;
    private static void eq(Object expected, Object actual, String message) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
    }
    private static void rejected(Method screenshot, Context context, String message) throws Exception {
        checks++;
        try {
            screenshot.invoke(null, context);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof IllegalStateException
                    && failure.getCause().getMessage().equals(message)) return;
            throw failure;
        }
        throw new AssertionError("Expected screenshot rejection: " + message);
    }
    public static void main(String[] args) throws Exception {
        String family = args[0];
        SystemProperties.reset();
        Build.FINGERPRINT = "generic/device/product:17/release-keys";
        Build.DISPLAY = "release"; Build.MANUFACTURER = "Xiaomi"; Build.BRAND = "Xiaomi";
        if ("COLOROS".equals(family)) SystemProperties.values.put("ro.build.version.coloros", "17");
        if ("HYPEROS".equals(family)) SystemProperties.values.put("ro.mi.os.version.name", "OS3.0");
        eq(family, RomProfile.detect().name(), "isolated screenshot family");
        Context context = new Context();
        ActionRunner.setSystemClassLoader(ActionRoutingTests.class.getClassLoader());
        Method screenshot = ActionRunner.class.getDeclaredMethod("screenshot", Context.class);
        screenshot.setAccessible(true);
        if ("UNKNOWN".equals(family)) {
            rejected(screenshot, context, "Partial screenshot is unavailable for this ROM");
            eq(0, ColorScreenshot.calls, "unknown ROM never invokes ColorOS screenshot");
            eq(0, ShortCutActionsUtils.calls, "unknown ROM never invokes MIUI screenshot");
        } else {
            ActionRunner.run(context, PenConfig.ACTION_SCREENSHOT, 0);
            eq("COLOROS".equals(family) ? 1 : 0, ColorScreenshot.calls, "ColorOS routing");
            eq("HYPEROS".equals(family) ? 1 : 0, ShortCutActionsUtils.calls, "MIUI routing");
            if ("HYPEROS".equals(family)) {
                eq("stylus_partial_screenshot", ShortCutActionsUtils.function, "native partial screenshot action");
                eq("long_press_page_up_key", ShortCutActionsUtils.reason, "native stylus source");
                eq(null, ShortCutActionsUtils.extras, "native screenshot extras");
                eq(false, ShortCutActionsUtils.haptic, "native screenshot feedback parameter");
                ShortCutActionsUtils.result = Boolean.FALSE;
                rejected(screenshot, context, "MIUI partial screenshot rejected");
                eq(0, ColorScreenshot.calls, "MIUI rejection never switches to ColorOS");
            }
        }
        System.out.println("PASS " + checks + " screenshot routing assertions (" + family + ")");
    }
}
