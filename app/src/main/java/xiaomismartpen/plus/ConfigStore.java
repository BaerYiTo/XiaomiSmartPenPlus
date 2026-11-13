package xiaomismartpen.plus;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

/** App-side persistence. system_server reads the same snapshot through {@link ConfigProvider}. */
final class ConfigStore {
    static final String AUTHORITY = "com.inkdye.xiaomipen.config";
    static final Uri SNAPSHOT_URI = Uri.parse("content://" + AUTHORITY + "/snapshot");
    static final String METHOD_SNAPSHOT = "snapshot";
    static final String KEY_SNAPSHOT = "snapshot";

    private static final String PREFS = "config";

    private ConfigStore() { }

    private static Context storageContext(Context context) {
        return context.createDeviceProtectedStorageContext();
    }

    static PenConfig load(Context context) {
        SharedPreferences prefs = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_SNAPSHOT, null);
        if (raw == null) {
            PenConfig initial = importPreviousPackage(context);
            prefs.edit().putString(KEY_SNAPSHOT, initial.serialize()).apply();
            return initial;
        }
        PenConfig parsed = PenConfig.parse(raw);
        String migrated = parsed.serialize();
        if (!migrated.equals(raw)) prefs.edit().putString(KEY_SNAPSHOT, migrated).apply();
        return parsed;
    }

    /** Only the first launch imports the old read-only snapshot; later edits stay independent. */
    private static PenConfig importPreviousPackage(Context context) {
        try {
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://com.focuspenlite.config/snapshot"), METHOD_SNAPSHOT, null, null);
            String raw = result == null ? null : result.getString(KEY_SNAPSHOT);
            if (raw != null && !raw.trim().isEmpty()) return PenConfig.parse(raw);
        } catch (RuntimeException unavailable) {
            // Old versions may be absent, inaccessible, or lack the snapshot endpoint.
        }
        return PenConfig.defaults();
    }

    static void save(Context context, PenConfig config) {
        boolean saved = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SNAPSHOT, config.serialize())
                .commit();
        if (!saved) throw new IllegalStateException("Configuration could not be saved");
        context.getContentResolver().notifyChange(SNAPSHOT_URI, null);
    }
}
