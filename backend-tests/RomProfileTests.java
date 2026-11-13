package xiaomismartpen.plus;

import android.os.Build;
import android.os.SystemProperties;
import de.robv.android.xposed.XposedHelpers;
import java.util.Collections;
import java.util.Objects;

/** First-load classification in a fresh JVM per case; checks call the production detector. */
public final class RomProfileTests {
    static int checks;
    static void eq(Object expected,Object actual,String message) {
        checks++;
        if(!Objects.equals(expected,actual)) throw new AssertionError(message+": expected="+expected+" actual="+actual);
    }
    static void setup(String testCase) {
        SystemProperties.reset();
        Build.FINGERPRINT="generic/device/product:17/release-keys";Build.DISPLAY="release";
        Build.MANUFACTURER="Xiaomi";Build.BRAND="Xiaomi";
        switch(testCase) {
            case "color-property": SystemProperties.values.put("ro.build.version.coloros"," 17 ");break;
            case "oplus-property": SystemProperties.values.put("ro.build.version.oplus","17");break;
            case "oplusrom-property": SystemProperties.values.put("ro.build.version.oplusrom","V17");break;
            case "opporom-property": SystemProperties.values.put("ro.build.version.opporom","V17");break;
            case "port-mixed":
                SystemProperties.values.put("ro.build.version.oplusrom","V17");
                SystemProperties.values.put("ro.mi.os.version.name","OS3.0");
                Build.FINGERPRINT="Xiaomi/liuqin/liuqin:16/HyperOS/release-keys";break;
            case "oneplus-fingerprint": Build.FINGERPRINT="OnePlus/OPD2513/OP6547L1:17/CP2A/release-keys";break;
            case "oppo-brand": Build.BRAND="OPPO";break;
            case "oneplus-manufacturer-miui":
                Build.MANUFACTURER="OnePlus";
                SystemProperties.values.put("ro.miui.ui.version.name","V14");break;
            case "oplus-brand": Build.BRAND="OPlus";break;
            case "oppo-fingerprint": Build.FINGERPRINT="OPPO/device/product:17/release-keys";break;
            case "property-denied-color": SystemProperties.failGet=true;Build.FINGERPRINT="OnePlus/device/product:17/release-keys";break;
            case "color-display": Build.DISPLAY="ColorOS 17";break;
            case "hyper-property": SystemProperties.values.put("ro.mi.os.version.name","OS3.0");break;
            case "hyper-code": SystemProperties.values.put("ro.mi.os.version.code","3");break;
            case "miui-property": SystemProperties.values.put("ro.miui.ui.version.name","V14");break;
            case "miui-code": SystemProperties.values.put("ro.miui.ui.version.code","14");break;
            case "hyper-fingerprint": Build.FINGERPRINT="Xiaomi/liuqin/liuqin:16/HyPeRoS/release-keys";break;
            case "miui-display": Build.DISPLAY="MIUI 14 release";break;
            case "property-denied": SystemProperties.failGet=true;break;
            case "property-denied-fingerprint": SystemProperties.failGet=true;Build.FINGERPRINT="Xiaomi/liuqin:16/HyperOS/release-keys";break;
            case "null-build": Build.FINGERPRINT=null;Build.DISPLAY=null;Build.MANUFACTURER=null;Build.BRAND=null;break;
            case "unknown":
                SystemProperties.values.put("ro.build.version.coloros","   ");
                SystemProperties.values.put("ro.mi.os.version.name","   ");break;
            default: throw new AssertionError("Unknown test case "+testCase);
        }
    }
    public static void main(String[] args) {
        setup(args[0]);
        eq(args[1],RomProfile.detect().name(),"first-load ROM classification for "+args[0]);
        PenKeyController.install(RomProfileTests.class.getClassLoader());
        String policy = "HYPEROS".equals(args[1])
                ? "com.android.server.policy.MiuiPhoneWindowManager"
                : "com.android.server.policy.PhoneWindowManager";
        String focus = "HYPEROS".equals(args[1])
                ? "com.miui.server.input.stylus.MiuiStylusShortcutManager"
                : "com.android.server.policy.PhoneWindowManager";
        eq(Collections.singletonList(policy),XposedHelpers.hookedTypes.get("interceptKeyBeforeQueueing"),"one ROM-specific policy entry");
        eq(Collections.singletonList(focus),XposedHelpers.hookedTypes.get("onDefaultDisplayFocusChangedLw"),"ROM-specific focus source");
        PenKeyController.install(RomProfileTests.class.getClassLoader());
        eq(Collections.singletonList(policy),XposedHelpers.hookedTypes.get("interceptKeyBeforeQueueing"),"install is idempotent");
        SystemProperties.reset();Build.FINGERPRINT="different/HyperOS";Build.DISPLAY="ColorOS";
        eq(args[1],RomProfile.detect().name(),"ROM family stays stable after process initialization");
        System.out.println("PASS "+checks+" ROM detection/policy assertions ("+args[0]+")");
    }
}
