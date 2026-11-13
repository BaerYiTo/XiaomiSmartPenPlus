package xiaomismartpen.plus;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class ModuleEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        try {
            if ("android".equals(param.packageName) && "android".equals(param.processName)) {
                PenKeyController.install(param.classLoader);
                Log.i("FocusPenLite", "FPL|module|system installed rom=" + RomProfile.detect());
            } else if (RomProfile.detect() == RomProfile.Family.COLOROS
                    && "com.oplus.ipemanager".equals(param.packageName)) {
                XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            IpeBridge.install(((Context) hook.args[0]).getClassLoader(), (Context) hook.args[0]);
                        }
                    });
            }
        } catch (Throwable failure) {
            XposedBridge.log("FocusPen module install failed: " + failure);
        }
    }
}
