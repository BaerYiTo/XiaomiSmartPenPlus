package xiaomismartpen.plus;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;

final class HardwareState {
    static final String KEY = "focuspen_hardware";
    final boolean connected, docked, valid;
    final int battery;
    final String mac;
    final long at;

    HardwareState(boolean connected, boolean docked, int battery, String mac, long at, boolean valid) {
        this.connected = connected; this.docked = docked; this.battery = battery;
        this.mac = mac; this.at = at; this.valid = valid;
    }
    boolean isFresh() {
        long age = SystemClock.elapsedRealtime() - at;
        return valid && age >= 0 && age < 45000;
    }
    static HardwareState read(Context context) {
        try {
            JSONObject raw = new JSONObject(Settings.Global.getString(context.getContentResolver(), KEY));
            long at = raw.optLong("at");
            String mac = raw.optString("mac");
            boolean valid = mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
                    && !mac.equals("00:00:00:00:00:00") && at > 0
                    && SystemClock.elapsedRealtime() - at >= 0 && SystemClock.elapsedRealtime() - at < 45000;
            int battery = raw.optInt("battery", -1);
            if (battery < 0 || battery > 100) battery = -1;
            if (!valid) battery = -1;
            return new HardwareState(valid && raw.optBoolean("connected"), valid && raw.optBoolean("docked"),
                    battery, mac, at, valid);
        } catch (Exception unavailable) { return new HardwareState(false, false, -1, "", 0, false); }
    }
}
