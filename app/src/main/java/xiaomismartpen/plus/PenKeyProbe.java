package xiaomismartpen.plus;

import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Observation-only build. Nothing here consumes or rewrites an event: the ROM's own pen-key
 * handling must keep working while we learn which layer actually decides the native action.
 *
 * Candidate layers (all found in miui-services.jar, com.android.server.* / com.miui.server.*):
 *   pwm      com.android.server.policy.MiuiPhoneWindowManager#interceptKeyBeforeQueueing(KeyEvent,int)
 *   stub     com.android.server.policy.PhoneWindowManagerStubImpl#interceptKeyBeforeQueueing(KeyEvent)
 *   film     com.miui.server.input.stylus.MiuiStylusTouchFilmManager#interceptKeyBeforeQueueing(KeyEvent,boolean)
 *   shortcut com.miui.server.input.stylus.MiuiStylusShortcutManager#interceptKeyByRemoteControl(KeyEvent)
 *   ims      com.android.server.input.InputManagerService#interceptKeyBeforeQueueing(KeyEvent,int)
 *
 * Each hook is installed independently; a missing class or signature is reported and skipped
 * instead of aborting the whole probe.
 */
final class PenKeyProbe {
    private static final String TAG = "FocusPenLite";
    private static final int KEY_PAGE_UP = 92;
    private static final int KEY_PAGE_DOWN = 93;
    private static boolean installed;

    private PenKeyProbe() { }

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        installed = true;

        dumpDevices();

        hook(loader, "pwm", "com.android.server.policy.MiuiPhoneWindowManager",
                "interceptKeyBeforeQueueing", KeyEvent.class, int.class);
        hook(loader, "stub", "com.android.server.policy.PhoneWindowManagerStubImpl",
                "interceptKeyBeforeQueueing", KeyEvent.class);
        hook(loader, "film", "com.miui.server.input.stylus.MiuiStylusTouchFilmManager",
                "interceptKeyBeforeQueueing", KeyEvent.class, boolean.class);
        hook(loader, "shortcut", "com.miui.server.input.stylus.MiuiStylusShortcutManager",
                "interceptKeyByRemoteControl", KeyEvent.class);
        hook(loader, "ims", "com.android.server.input.InputManagerService",
                "interceptKeyBeforeQueueing", KeyEvent.class, int.class);
    }

    private static void hook(ClassLoader loader, final String label, String className,
                             String methodName, Class<?>... params) {
        try {
            Object[] args = new Object[params.length + 1];
            System.arraycopy(params, 0, args, 0, params.length);
            args[params.length] = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    logEvent(label + ":in", param.args[0]);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    logEvent(label + ":out", param.args[0], param.getResult());
                }
            };
            XposedHelpers.findAndHookMethod(className, loader, methodName, args);
            Log.i(TAG, "FPL|hook|" + label + "|installed");
            XposedBridge.log(TAG + " hook " + label + " installed");
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|hook|" + label + "|MISSING " + failure.getClass().getSimpleName());
            XposedBridge.log(TAG + " hook " + label + " missing: " + failure);
        }
    }

    private static void logEvent(String stage, Object rawEvent) {
        logEvent(stage, rawEvent, null);
    }

    private static void logEvent(String stage, Object rawEvent, Object result) {
        try {
            KeyEvent event = (KeyEvent) rawEvent;
            if (!isPenKey(event)) return;
            InputDevice device = event.getDevice();
            Log.i(TAG, "FPL|" + stage
                    + "|dev=" + (device == null ? "?" : device.getName())
                    + "|id=" + (device == null ? -1 : device.getId())
                    + "|code=" + event.getKeyCode()
                    + "|action=" + (event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                    + "|repeat=" + event.getRepeatCount()
                    + "|policy=0x" + Integer.toHexString(event.getFlags())
                    + "|down=" + event.getDownTime()
                    + "|at=" + event.getEventTime()
                    + (result == null ? "" : "|res=" + result));
        } catch (Throwable ignored) { }
    }

    /** The pen buttons are the only keys we care about: PAGE_UP / PAGE_DOWN from the pen keyboard. */
    private static boolean isPenKey(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();
        if (code == KEY_PAGE_UP || code == KEY_PAGE_DOWN) return true;
        InputDevice device = event.getDevice();
        String name = device == null ? null : device.getName();
        return name != null && name.toLowerCase(java.util.Locale.ROOT).contains("pen");
    }

    /** One-shot inventory so the log shows which device actually carries the buttons. */
    private static void dumpDevices() {
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null) continue;
                Log.i(TAG, "FPL|device|id=" + id + "|name=" + device.getName()
                        + "|sources=0x" + Integer.toHexString(device.getSources())
                        + "|vendor=" + device.getVendorId() + "|product=" + device.getProductId());
                XposedBridge.log(TAG + " device id=" + id + " name=" + device.getName()
                        + " sources=0x" + Integer.toHexString(device.getSources()));
            }
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|cannot enumerate input devices", failure);
        }
    }
}
