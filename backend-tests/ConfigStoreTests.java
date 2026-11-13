package xiaomismartpen.plus;

import android.content.Context;
import android.content.ContentResolver;
import java.util.Objects;

/** Tests the production ConfigStore's one-time import and package-scoped persistence behavior. */
public final class ConfigStoreTests {
    static int checks;
    static void eq(Object expected, Object actual, String message) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(message
                + ": expected=" + expected + " actual=" + actual);
    }
    static void yes(boolean actual, String message) { eq(true, actual, message); }
    static PenConfig active(String action) {
        PenConfig p = PenConfig.defaults();
        p.setTakeover(PenConfig.B1, true);
        p.setAction(PenConfig.B1, PenConfig.SHORT, action);
        return p;
    }
    static String saved(Context context) {
        return context.getSharedPreferences("config", Context.MODE_PRIVATE)
                .getString(ConfigStore.KEY_SNAPSHOT, null);
    }
    static void firstImport() {
        ContentResolver.reset();
        Context context = new Context();
        PenConfig old = active(PenConfig.ACTION_SWIPE_DOWN);
        old.longPressMs = 850;
        old.ensureAppConfig("com.example.reader").setAction(
                PenConfig.B1, PenConfig.SHORT, PenConfig.ACTION_PAGE_DOWN);
        String oldRaw = old.serialize() + "apps.native=com.example.notes\n";
        ContentResolver.snapshot = oldRaw;
        PenConfig imported = ConfigStore.load(context);
        eq(PenConfig.ACTION_SWIPE_DOWN, imported.action(PenConfig.B1, PenConfig.SHORT), "first load imports old global mapping");
        eq(850L, imported.longPressMs, "first load imports old threshold");
        eq(PenConfig.ACTION_PAGE_DOWN, imported.appConfig("com.example.reader").action(PenConfig.B1, PenConfig.SHORT), "first load imports app profile independently");
        yes(!imported.appConfig("com.example.notes").takeover(PenConfig.B1), "import migrates old exclusion to a native app profile");
        eq(PenConfig.parse(oldRaw).serialize(), saved(context), "import stores canonical snapshot in new app preferences");
        eq(1, ContentResolver.snapshotReads, "first import reads old provider once");
        eq("content://com.focuspenlite.config/snapshot", ContentResolver.lastCallUri, "migration targets only previous provider URI");
        eq("snapshot", ContentResolver.lastMethod, "migration uses existing read-only snapshot API");
        eq(1, context.memoryPreferences("config").applies, "first import persists exactly once");

        ContentResolver.snapshot = active(PenConfig.ACTION_VOLUME_UP).serialize();
        eq(imported.serialize(), ConfigStore.load(context).serialize(), "later old-app changes do not override imported new app settings");
        eq(1, ContentResolver.snapshotReads, "subsequent load never reimports old provider");
        eq(1, context.memoryPreferences("config").applies, "canonical reload does not rewrite unchanged preferences");

        PenConfig edited = imported.copy();
        edited.setAction(PenConfig.B1, PenConfig.SHORT, PenConfig.ACTION_HOME);
        edited.removeAppConfig("com.example.reader");
        String unchangedOld = ContentResolver.snapshot;
        ConfigStore.save(context, edited);
        eq(edited.serialize(), saved(context), "new app edits persist independently");
        eq(unchangedOld, ContentResolver.snapshot, "new app save does not write to previous provider");
        eq(1, ContentResolver.changes, "save emits one change notification");
        eq("content://com.inkdye.xiaomipen.config/snapshot", ContentResolver.lastNotifyUri, "save notifies the new package authority");
        eq(edited.serialize(), ConfigStore.load(context).serialize(), "saved new edits survive reload");
        eq(1, ContentResolver.snapshotReads, "new edits never trigger a second import");
    }
    static void existingNewConfig() {
        ContentResolver.reset();
        Context context = new Context();
        PenConfig existing = active(PenConfig.ACTION_BACK);
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                .putString(ConfigStore.KEY_SNAPSHOT, existing.serialize()).apply();
        ContentResolver.snapshot = active(PenConfig.ACTION_HOME).serialize();
        eq(existing.serialize(), ConfigStore.load(context).serialize(), "preexisting new-package configuration takes priority");
        eq(0, ContentResolver.snapshotReads, "preexisting new configuration never contacts old provider");
    }
    static void unavailableOldProvider() {
        ContentResolver.reset();
        Context context = new Context();
        ContentResolver.throwSnapshot = true;
        PenConfig fallback = ConfigStore.load(context);
        eq(PenConfig.defaults().serialize(), fallback.serialize(), "missing old provider falls back safely to native defaults");
        yes(!fallback.owns(PenConfig.B1) && !fallback.owns(PenConfig.B2), "old provider failure cannot enable takeover");
        eq(fallback.serialize(), saved(context), "fallback is persisted as new configuration");
        ContentResolver.throwSnapshot = false;
        ContentResolver.snapshot = active(PenConfig.ACTION_VOLUME_DOWN).serialize();
        eq(fallback.serialize(), ConfigStore.load(context).serialize(), "later provider recovery does not overwrite already-created defaults");
        eq(1, ContentResolver.snapshotReads, "provider recovery does not silently repeat one-time import");

        ContentResolver.reset(); ContentResolver.failSnapshot = true;
        eq(PenConfig.defaults().serialize(), ConfigStore.load(new Context()).serialize(), "null bundle uses native fallback");
        ContentResolver.reset();
        eq(PenConfig.defaults().serialize(), ConfigStore.load(new Context()).serialize(), "missing snapshot field uses native fallback");
        ContentResolver.reset(); ContentResolver.snapshot = "  \n\t ";
        eq(PenConfig.defaults().serialize(), ConfigStore.load(new Context()).serialize(), "blank old snapshot uses native fallback");
    }
    public static void main(String[] args) {
        firstImport(); existingNewConfig(); unavailableOldProvider();
        System.out.println("PASS " + checks + " ConfigStore migration assertions (production persistence)");
    }
}
