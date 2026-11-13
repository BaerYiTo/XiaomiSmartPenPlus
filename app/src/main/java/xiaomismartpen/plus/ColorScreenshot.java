package xiaomismartpen.plus;

import android.content.Context;
import android.os.Bundle;
import de.robv.android.xposed.XposedHelpers;

/** 当前 ROM 的截图服务按来源选择手写笔区域编辑器。 */
final class ColorScreenshot {
    private ColorScreenshot() { }

    static void run(Context context, ClassLoader loader) {
        Class<?> type = XposedHelpers.findClass("com.oplus.screenshot.OplusScreenshotManager", loader);
        Object manager = XposedHelpers.callStaticMethod(type, "getInstance");
        Bundle extras = new Bundle();
        // PencilAreaScreenshot 只捕获位图，需额外原厂笔面板；区域入口会直接显示选择器。
        extras.putString("screenshot_source", "AreaScreenshot_focuspen");
        extras.putInt("screenshot_orientation", context.getResources().getConfiguration().orientation);
        XposedHelpers.callMethod(manager, "takeScreenshot", extras);
    }
}
