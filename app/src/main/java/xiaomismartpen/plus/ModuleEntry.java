package xiaomismartpen.plus;

import android.os.Process;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point. Only system_server is handled: pen buttons arrive there as normal key events
 * from the "Xiaomi Smart Pen Keyboard" input device.
 */
public final class ModuleEntry implements IXposedHookLoadPackage {
    static final String TAG = "FocusPenLite";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"android".equals(param.packageName) || !"android".equals(param.processName)) return;
        XposedBridge.log(TAG + " probe entry pid=" + Process.myPid());
        try {
            PenKeyProbe.install(param.classLoader);
            XposedBridge.log(TAG + " probe installed");
            PenKeyController.install(param.classLoader);
        } catch (Throwable failure) {
            XposedBridge.log(TAG + " probe install failed; system behaviour untouched");
            XposedBridge.log(failure);
        }
    }
}
