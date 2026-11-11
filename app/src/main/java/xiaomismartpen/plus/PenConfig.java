package xiaomismartpen.plus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * The module's whole configuration: which gesture of which button runs which action, plus the two
 * timing thresholds. Deliberately a flat string so both processes (the config UI and system_server)
 * can share one parser and one representation.
 *
 * Format: newline separated {@code key=value} pairs.
 *   b1.takeover=true
 *   b1.short=volume_up
 *   b1.long=back
 *   b1.double=none
 *   b2.*=...
 *   timing.long=400
 *   timing.double=300
 */
final class PenConfig {
    static final String B1 = "b1";
    static final String B2 = "b2";
    static final String SHORT = "short";
    static final String LONG = "long";
    static final String DOUBLE = "double";
    static final String[] SLOTS = { SHORT, LONG, DOUBLE };

    static final String ACTION_NONE = "none";
    static final String ACTION_VOLUME_UP = "volume_up";
    static final String ACTION_VOLUME_DOWN = "volume_down";
    static final String ACTION_PLAY_PAUSE = "play_pause";
    static final String ACTION_BACK = "back";
    static final String ACTION_HOME = "home";
    static final String ACTION_RECENTS = "recents";
    static final String ACTION_PAGE_UP = "page_up";
    static final String ACTION_PAGE_DOWN = "page_down";
    /** Synthetic touch gesture. Feed apps (抖音 / 小红书 …) only navigate on touch, not on page keys. */
    static final String ACTION_SWIPE_UP = "swipe_up";
    static final String ACTION_SWIPE_DOWN = "swipe_down";
    /** Horizontal variants, for novel readers that page left/right instead of scrolling. */
    static final String ACTION_SWIPE_LEFT = "swipe_left";
    static final String ACTION_SWIPE_RIGHT = "swipe_right";
    static final String ACTION_SCREENSHOT = "screenshot_partial";
    /** Hand the untouched pen key to the foreground app, for apps that map it themselves. */
    static final String ACTION_PASSTHROUGH = "passthrough";
    static final String ACTION_LAUNCH_PREFIX = "launch:";

    /** Each app stores a complete configuration; absent apps use the global settings. */
    private static final String APP_PREFIX = "app.";

    /** Action ids offered in the config UI, in display order. */
    static final String[] ACTIONS = {
            ACTION_NONE,
            ACTION_VOLUME_UP,
            ACTION_VOLUME_DOWN,
            ACTION_PLAY_PAUSE,
            ACTION_BACK,
            ACTION_HOME,
            ACTION_RECENTS,
            ACTION_PAGE_UP,
            ACTION_PAGE_DOWN,
            ACTION_SWIPE_UP,
            ACTION_SWIPE_DOWN,
            ACTION_SWIPE_LEFT,
            ACTION_SWIPE_RIGHT,
            ACTION_SCREENSHOT,
            ACTION_PASSTHROUGH,
    };

    /** Chinese labels for {@link #ACTIONS}; the indexes must line up. */
    static final String[] ACTION_LABELS = {
            "不执行",
            "音量加",
            "音量减",
            "播放/暂停",
            "返回",
            "桌面",
            "最近任务",
            "上翻页",
            "下翻页",
            "上滑",
            "下滑",
            "左滑",
            "右滑",
            "区域截屏",
            "透传给应用",
    };

    static final String DEFAULT_APP_ACTION_LABEL = "启动应用";

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, PenConfig> apps = new LinkedHashMap<>();

    long longPressMs = 400L;
    long doubleWindowMs = 300L;

    static PenConfig defaults() {
        PenConfig config = new PenConfig();
        // Takeover is off by default: with the module installed and nothing configured, the pen
        // must behave exactly as it does without the module.
        config.setTakeover(B1, false);
        config.setTakeover(B2, false);
        config.put(B1, SHORT, ACTION_NONE);
        config.put(B1, LONG, ACTION_BACK);
        config.put(B1, DOUBLE, ACTION_NONE);
        config.put(B2, SHORT, ACTION_VOLUME_UP);
        config.put(B2, LONG, ACTION_HOME);
        config.put(B2, DOUBLE, ACTION_PLAY_PAUSE);
        config.longPressMs = 400L;
        config.doubleWindowMs = 300L;
        return config;
    }

    static PenConfig parse(String raw) {
        PenConfig config = defaults();
        if (raw == null || raw.isEmpty()) return config;
        List<String> legacyNativeApps = new ArrayList<>();
        for (String line : raw.split("\n")) {
            int split = line.indexOf('=');
            if (split <= 0) continue;
            String key = line.substring(0, split).trim();
            String value = line.substring(split + 1).trim();
            if (key.startsWith(APP_PREFIX)) {
                String pkg = key.substring(APP_PREFIX.length());
                if (!validPackage(pkg)) continue;
                try {
                    String profile = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                    // App profiles contain settings only, so malformed nested data cannot recurse.
                    PenConfig app = defaults();
                    for (String setting : profile.split("\n")) {
                        int equal = setting.indexOf('=');
                        if (equal > 0) app.readSetting(setting.substring(0, equal).trim(),
                                setting.substring(equal + 1).trim());
                    }
                    config.apps.put(pkg, app);
                } catch (IllegalArgumentException ignored) { }
            } else if (key.equals("apps.native")) {
                for (String pkg : value.split(",")) {
                    String trimmed = pkg.trim();
                    if (validPackage(trimmed)) legacyNativeApps.add(trimmed);
                }
            } else {
                config.readSetting(key, value);
            }
        }
        // Preserve the old behavior through ordinary app configurations, then discard the old key.
        for (String pkg : legacyNativeApps) {
            if (config.apps.containsKey(pkg)) continue;
            PenConfig app = config.settingsCopy();
            app.setTakeover(B1, false);
            app.setTakeover(B2, false);
            config.apps.put(pkg, app);
        }
        return config;
    }

    private void readSetting(String key, String value) {
        if (key.equals("timing.long")) {
            longPressMs = clamp(parseLong(value, 400L), 200L, 1500L);
        } else if (key.equals("timing.double")) {
            doubleWindowMs = clamp(parseLong(value, 300L), 150L, 800L);
        } else if (isActionKey(key)) {
            values.put(key, isKnownAction(value) ? value : ACTION_NONE);
        } else if (key.equals(B1 + ".takeover") || key.equals(B2 + ".takeover")) {
            values.put(key, "true".equals(value) ? "true" : "false");
        }
    }

    private String serializeSettings() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            builder.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        builder.append("timing.long=").append(clamp(longPressMs, 200L, 1500L)).append('\n');
        builder.append("timing.double=").append(clamp(doubleWindowMs, 150L, 800L)).append('\n');
        return builder.toString();
    }

    String serialize() {
        StringBuilder builder = new StringBuilder(serializeSettings());
        for (Map.Entry<String, PenConfig> entry : apps.entrySet()) {
            builder.append(APP_PREFIX).append(entry.getKey()).append('=')
                    .append(Base64.getEncoder().encodeToString(
                            entry.getValue().serializeSettings().getBytes(StandardCharsets.UTF_8)))
                    .append('\n');
        }
        return builder.toString();
    }

    PenConfig appConfig(String pkg) {
        return pkg == null ? null : apps.get(pkg);
    }

    PenConfig ensureAppConfig(String pkg) {
        if (!validPackage(pkg)) throw new IllegalArgumentException("Invalid application package");
        PenConfig app = apps.get(pkg);
        if (app == null) {
            app = settingsCopy();
            apps.put(pkg, app);
        }
        return app;
    }

    void setAppConfig(String pkg, PenConfig app) {
        if (!validPackage(pkg)) throw new IllegalArgumentException("Invalid application package");
        if (app == null) apps.remove(pkg);
        else apps.put(pkg, app.settingsCopy());
    }

    void removeAppConfig(String pkg) {
        apps.remove(pkg);
    }

    List<String> appPackages() {
        return new ArrayList<>(apps.keySet());
    }

    PenConfig resolveForPackage(String pkg) {
        PenConfig app = appConfig(pkg);
        return app == null ? this : app;
    }

    private PenConfig settingsCopy() {
        PenConfig result = new PenConfig();
        result.values.putAll(values);
        result.longPressMs = longPressMs;
        result.doubleWindowMs = doubleWindowMs;
        return result;
    }

    PenConfig copy() {
        PenConfig result = settingsCopy();
        for (Map.Entry<String, PenConfig> entry : apps.entrySet()) {
            result.apps.put(entry.getKey(), entry.getValue().settingsCopy());
        }
        return result;
    }

    private static boolean validPackage(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    }

    boolean takeover(String button) {
        return "true".equals(values.get(button + ".takeover"));
    }

    void setTakeover(String button, boolean enabled) {
        values.put(button + ".takeover", enabled ? "true" : "false");
    }

    String action(String button, String slot) {
        String value = values.get(button + "." + slot);
        return value == null ? ACTION_NONE : value;
    }

    void setAction(String button, String slot, String action) {
        if (!isActionKey(button + "." + slot)) return;
        values.put(button + "." + slot, action != null && isKnownAction(action) ? action : ACTION_NONE);
    }

    private void put(String button, String slot, String action) {
        values.put(button + "." + slot, action);
    }

    /** A key is only owned when takeover is on and at least one slot has a real action. */
    boolean owns(String button) {
        if (!takeover(button)) return false;
        for (String slot : SLOTS) {
            if (!ACTION_NONE.equals(action(button, slot))) return true;
        }
        return false;
    }

    static String buttonFor(int keyCode) {
        if (keyCode == PenKeyController.KEY_BUTTON_1) return B1;
        if (keyCode == PenKeyController.KEY_BUTTON_2) return B2;
        return null;
    }

    static String labelFor(String action) {
        if (action == null) return ACTION_LABELS[0];
        if (action.startsWith(ACTION_LAUNCH_PREFIX)) {
            return DEFAULT_APP_ACTION_LABEL + "：" + action.substring(ACTION_LAUNCH_PREFIX.length());
        }
        for (int i = 0; i < ACTIONS.length; i++) {
            if (ACTIONS[i].equals(action)) return ACTION_LABELS[i];
        }
        return ACTION_LABELS[0];
    }

    /** A stale snapshot can name an action this build no longer has; treat those as 不执行. */
    private static boolean isActionKey(String key) {
        return key.equals(B1 + "." + SHORT) || key.equals(B1 + "." + LONG)
                || key.equals(B1 + "." + DOUBLE) || key.equals(B2 + "." + SHORT)
                || key.equals(B2 + "." + LONG) || key.equals(B2 + "." + DOUBLE);
    }

    private static boolean isKnownAction(String value) {
        if (value.startsWith(ACTION_LAUNCH_PREFIX)) {
            return validPackage(value.substring(ACTION_LAUNCH_PREFIX.length()));
        }
        for (String action : ACTIONS) {
            if (action.equals(value)) return true;
        }
        return false;
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long clamp(long value, long min, long max) {
        return value < min ? min : (value > max ? max : value);
    }
}
