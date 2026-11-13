package xiaomismartpen.plus;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

/** ColorOS 手写笔系统页面入口，其他系统由其原生设置管理。 */
final class SystemSettings {
    private SystemSettings() { }

    static void open(Context context) {
        if (RomProfile.detect() != RomProfile.Family.COLOROS) {
            throw new ActivityNotFoundException("ColorOS stylus settings are unavailable on this ROM");
        }
        context.startActivity(colorOsIntent());
    }

    static Intent colorOsIntent() {
        return new Intent().setClassName("com.oplus.ipemanager",
                "com.oplus.ipemanager.btadsorb.setting.activity.PencilSettingActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
