package xiaomismartpen.plus;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Owns the two pen buttons and replaces the ROM's actions with the configured ones.
 *
 * Hook point: ROM 对应的 PhoneWindowManager#interceptKeyBeforeQueueing(KeyEvent,int)。
 *   return 1 = the event continues to the user, return 0 = the event is dropped.
 * HyperOS 在 MiuiPhoneWindowManager 进入笔快捷键前拦截；ColorOS 保留通用策略入口。
 * 每次仅注册一种策略入口，避免父类和子类同时识别同一手势。
 * 完整透传通过系统输入过滤器使用的发送路径抵达应用。Measured on the
 * device: lower button = PAGE_DOWN, upper = PAGE_UP on the reference ROM. The ColorOS port
 * maps Linux 109 to BUTTON_2 and Linux 104 to BUTTON_1; logical button order stays unchanged.
 *
 * Safety rules, all load-bearing:
 *  - a button is only touched when the user enabled takeover for it AND gave it at least one action
 *    ({@link PenConfig#owns}); otherwise the native path runs untouched;
 *  - the config is read on a background thread and cached, never over IPC on the input thread;
 *  - 接管前失败保留系统路径；已接管的手势失败时取消计时任务并消费剩余事件，避免重复动作。
 */
final class PenKeyController {
    private static final String TAG = "FocusPenLite";

    static final int KEY_BUTTON_1 = KeyEvent.KEYCODE_PAGE_DOWN;
    static final int KEY_BUTTON_2 = KeyEvent.KEYCODE_PAGE_UP;
    static final int KEY_COLOROS_LOWER = KeyEvent.KEYCODE_BUTTON_2;
    static final int KEY_COLOROS_UPPER = KeyEvent.KEYCODE_BUTTON_1;
    private static final String PEN_DEVICE = "Xiaomi Smart Pen Keyboard";
    /** WindowManagerPolicyConstants.FLAG_INJECTED，当前 ROM 的策略入口也使用该值。 */
    private static final int POLICY_FLAG_INJECTED = 0x01000000;

    private static boolean installed;
    private static Handler handler;
    private static Handler configHandler;
    private static volatile PenConfig config = PenConfig.defaults();
    private static volatile long configLoadedAt;
    private static final String CONFIG_CACHE_KEY = "focuspen_config_snapshot";
    private static String lastSnapshot;
    private static final long CONFIG_RETRY_MS = 2000L;
    private static final long CONFIG_REFRESH_MS = 5000L;
    /** Observer lifecycle and the single polling chain are confined to configHandler. */
    private static boolean observerRegistered;
    private static ContentObserver configObserver;

    private static final Map<Integer, KeyState> STATES = new HashMap<>();
    private static final Set<String> FILTERED_KEYS = new LinkedHashSet<>();

    private PenKeyController() { }

    /** Per-button gesture state. */
    private static final class KeyState {
        boolean down;
        boolean owned;
        boolean cancelled;
        boolean longFired;
        boolean secondPress;
        boolean forwarding;
        long pressDownTime;
        KeyEvent pressEvent;
        final List<KeyEvent> events = new ArrayList<>();
        PenConfig gestureConfig;
        String packageName;
        long focusEpoch;
        long taskToken;
        Runnable longTask;
        Runnable shortTask;
    }

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        installed = true;
        handler = new Handler(Looper.getMainLooper());
        ActionRunner.setSystemClassLoader(loader);

        HandlerThread configThread = new HandlerThread("FocusPenLite-config");
        configThread.start();
        configHandler = new Handler(configThread.getLooper());
        configHandler.post(PenKeyController::watchConfig);

        String policyClass = RomProfile.detect() == RomProfile.Family.HYPEROS
                ? "com.android.server.policy.MiuiPhoneWindowManager"
                : "com.android.server.policy.PhoneWindowManager";
        try {
            XposedHelpers.findAndHookMethod(
                    policyClass, loader,
                    "interceptKeyBeforeQueueing", KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            KeyEvent event = (KeyEvent) param.args[0];
                            handle(param, event);
                            if (Integer.valueOf(0).equals(param.getResult())) {
                                synchronized (FILTERED_KEYS) {
                                    if (FILTERED_KEYS.size() >= 128) FILTERED_KEYS.clear();
                                    FILTERED_KEYS.add(eventIdentity(event));
                                }
                            }
                        }
                    });
            Log.i(TAG, "FPL|controller|installed rom=" + RomProfile.detect() + " policy=" + policyClass);
            XposedBridge.log(TAG + " controller installed policy=" + policyClass);
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|controller|MISSING " + failure.getClass().getSimpleName());
            XposedBridge.log(TAG + " controller missing: " + failure);
        }
        installFocusTracking(loader);
        installDeviceTracking();
        installConsumedEventFilter(loader);
    }

    private static String eventIdentity(KeyEvent event) {
        return event.getDeviceId() + ":" + event.getEventTime() + ":" + event.getKeyCode()
                + ":" + event.getAction() + ":" + event.getRepeatCount();
    }

    private static void installConsumedEventFilter(ClassLoader loader) {
        try {
            Class<?> manager = XposedHelpers.findClass("com.android.server.input.InputManagerService", loader);
            XposedHelpers.findAndHookMethod(manager, "filterInputEvent", InputEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof KeyEvent)) return;
                            KeyEvent event = (KeyEvent) param.args[0];
                            synchronized (FILTERED_KEYS) {
                                if (FILTERED_KEYS.remove(eventIdentity(event))) {
                                    XposedHelpers.callMethod(event, "recycle");
                                    param.setResult(false);
                                }
                            }
                        }
                    });
            XC_MethodHook ensureEnabled = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object nativeService = XposedHelpers.getObjectField(param.thisObject, "mNative");
                        XposedHelpers.callMethod(nativeService, "setInputFilterEnabled", true);
                        Log.i(TAG, "FPL|filter|pen event filter active");
                    } catch (Throwable failure) {
                        Log.w(TAG, "FPL|filter|activation failed", failure);
                    }
                }
            };
            XposedBridge.hookAllMethods(manager, "start", ensureEnabled);
            XposedBridge.hookAllMethods(manager, "setInputFilter", ensureEnabled);
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|filter|installation failed", failure);
        }
    }

    // ---------------------------------------------------------------- foreground app

    /** Package of the window that currently has focus; refreshed from the ROM's own callback. */
    private static volatile String focusedPackage;
    /** Guarded by STATES, including changes from A to B and back to A. */
    private static long focusEpoch;

    private static void updateFocus(String pkg) {
        synchronized (STATES) {
            if (Objects.equals(pkg, focusedPackage)) return;
            focusedPackage = pkg;
            focusEpoch++;
            for (KeyState state : STATES.values()) {
                finishForwardedPress(state);
                cancelTasks(state);
                // An intercepted DOWN still needs its UP swallowed, even after the app changes.
                state.cancelled = state.down && state.owned;
                state.secondPress = false;
                state.forwarding = false;
                state.events.clear();
            }
        }
        Log.i(TAG, "FPL|focus|" + pkg);
    }

    private static void installFocusTracking(ClassLoader loader) {
        try {
            String focusClass = RomProfile.detect() == RomProfile.Family.HYPEROS
                    ? "com.miui.server.input.stylus.MiuiStylusShortcutManager"
                    : "com.android.server.policy.PhoneWindowManager";
            Class<?> manager = XposedHelpers.findClass(
                    focusClass, loader);
            // Signature differs between ROMs, so match by name rather than by parameter list.
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(manager, "onDefaultDisplayFocusChangedLw", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    updateFocus(param.args.length == 0 ? null : packageOf(param.args[0]));
                }
            });
            if (hooks.isEmpty()) throw new NoSuchMethodException(focusClass + "#onDefaultDisplayFocusChangedLw");
            Log.i(TAG, "FPL|focus|tracking installed class=" + focusClass);
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|focus|tracking MISSING " + failure.getClass().getSimpleName());
        }
    }

    private static void installDeviceTracking() {
        configHandler.post(new Runnable() {
            @Override public void run() {
                try {
                Context context = systemContext();
                if (context == null) { configHandler.postDelayed(this, 2000); return; }
                android.hardware.input.InputManager input = (android.hardware.input.InputManager)
                        context.getSystemService(Context.INPUT_SERVICE);
                if (input == null) { configHandler.postDelayed(this, 2000); return; }
                final Set<Integer> pens = new HashSet<>();
                for (int id : input.getInputDeviceIds()) {
                    InputDevice device = input.getInputDevice(id);
                    if (device != null && PEN_DEVICE.equals(device.getName())) pens.add(id);
                }
                input.registerInputDeviceListener(new android.hardware.input.InputManager.InputDeviceListener() {
                    private boolean isPen(int id) {
                        try {
                            InputDevice device = input.getInputDevice(id);
                            return device != null && PEN_DEVICE.equals(device.getName());
                        } catch (Throwable unavailable) { return false; }
                    }
                    @Override public void onInputDeviceAdded(int id) {
                        if (isPen(id)) { pens.add(id); resetInputState(); }
                    }
                    @Override public void onInputDeviceRemoved(int id) {
                        if (pens.remove(id)) resetInputState();
                    }
                    @Override public void onInputDeviceChanged(int id) {
                        boolean previous = pens.contains(id), current = isPen(id);
                        if (current) pens.add(id); else pens.remove(id);
                        if (previous || current) resetInputState();
                    }
                }, configHandler);
                Log.i(TAG, "FPL|device|listener installed");
                } catch (Throwable notReady) {
                    Log.w(TAG, "FPL|device|input service not ready; retrying");
                    configHandler.postDelayed(this, 2000);
                }
            }
        });
    }

    private static void resetInputState() {
        synchronized (STATES) {
            focusEpoch++;
            for (KeyState state : STATES.values()) {
                cancelTasks(state);
                state.down = false;
                state.owned = false;
                state.cancelled = false;
                state.secondPress = false;
                state.longFired = false;
                state.forwarding = false;
                state.events.clear();
            }
        }
    }

    private static String packageOf(Object windowState) {
        if (windowState == null) return null;
        try {
            Object attrs = XposedHelpers.callMethod(windowState, "getAttrs");
            Object pkg = XposedHelpers.getObjectField(attrs, "packageName");
            if (pkg instanceof String && !((String) pkg).isEmpty()) return (String) pkg;
        } catch (Throwable ignored) { }
        try {
            Object pkg = XposedHelpers.callMethod(windowState, "getOwningPackage");
            if (pkg instanceof String) return (String) pkg;
        } catch (Throwable ignored) { }
        return null;
    }

    // ---------------------------------------------------------------- config plumbing

    private static void watchConfig() {
        boolean loaded = false;
        try {
        final Context context = systemContext();
        if (context == null) {
            if (waitAttempts++ % 5 == 0) {
                Log.i(TAG, "FPL|config|no context yet, attempt=" + waitAttempts);
            }
        } else {
            // A provider may become reachable only after unlock. Register immediately after each
            // attempt, including the first successful read, rather than abandoning registration
            // when it failed early during boot.
            loaded = reloadConfig(context);
            ensureConfigObserver(context);
        }
        // Exactly one background polling chain: boot failures retry quickly; normal operation
        // also refreshes independently of pen input in case notifications are ever lost.
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|config|watch pending", failure);
        } finally {
        configHandler.postDelayed(PenKeyController::watchConfig,
                loaded && observerRegistered ? CONFIG_REFRESH_MS : CONFIG_RETRY_MS);
        }
    }

    private static void ensureConfigObserver(final Context context) {
        if (observerRegistered) return;
        if (configObserver == null) {
            configObserver = new ContentObserver(configHandler) {
                @Override public void onChange(boolean selfChange) {
                    Log.i(TAG, "FPL|config|observer fired");
                    reloadConfig(context);
                }
            };
        }
        try {
            context.getContentResolver().registerContentObserver(ConfigStore.SNAPSHOT_URI, false,
                    configObserver);
            observerRegistered = true;
            Log.i(TAG, "FPL|config|observer registered");
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|config|observer failed; retrying: "
                    + failure.getClass().getSimpleName());
        }
    }

    /** @return true when a snapshot was read. */
    private static boolean reloadConfig(Context context) {
        try {
            Bundle result = context.getContentResolver()
                    .call(ConfigStore.SNAPSHOT_URI, ConfigStore.METHOD_SNAPSHOT, null, null);
            String raw = result == null ? null : result.getString(ConfigStore.KEY_SNAPSHOT);
            if (raw == null) throw new IllegalStateException("Empty configuration snapshot");
            applySnapshot(context, raw, false);
            try {
                if (!raw.equals(Settings.Global.getString(context.getContentResolver(), CONFIG_CACHE_KEY))) {
                    if (!Settings.Global.putString(context.getContentResolver(), CONFIG_CACHE_KEY, raw)) {
                        Log.w(TAG, "FPL|config|cache write rejected");
                    }
                }
            } catch (Throwable cacheFailure) {
                Log.w(TAG, "FPL|config|cache write failed", cacheFailure);
            }
            return true;
        } catch (Throwable failure) {
            // 被系统强停的应用无法启动 provider；重启后仍采用上次实际读取的配置。
            // 缓存只由 system_server 在成功读取 provider 后写入，UI 的持久化仍是权威来源。
            try {
                String cached = Settings.Global.getString(context.getContentResolver(), CONFIG_CACHE_KEY);
                if (cached != null && !cached.trim().isEmpty()) {
                    applySnapshot(context, cached, true);
                    return false;
                }
            } catch (Throwable unavailable) {
                Log.w(TAG, "FPL|config|cache unavailable", unavailable);
            }
            Log.w(TAG, "FPL|config|reload failed: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static void applySnapshot(Context context, String raw, boolean cached) {
        if (!raw.equals(lastSnapshot)) {
            PenConfig loaded = PenConfig.parse(raw);
            config = loaded;
            SystemPenBridge.configure(context, loaded);
            lastSnapshot = raw;
            Log.i(TAG, "FPL|config|loaded source=" + (cached ? "cache" : "provider")
                    + " b1=" + loaded.owns(PenConfig.B1) + " b2=" + loaded.owns(PenConfig.B2)
                    + " apps=" + loaded.appPackages().size()
                    + " long=" + loaded.longPressMs + " double=" + loaded.doubleWindowMs);
        }
        configLoadedAt = android.os.SystemClock.uptimeMillis();
    }

    private static int waitAttempts;

    private static volatile Context cachedContext;

    private static Context systemContext() {
        if (cachedContext != null) return cachedContext;
        cachedContext = fromActivityThreadMethod();
        if (cachedContext == null) cachedContext = fromActivityThreadField();
        if (cachedContext != null) {
            Log.i(TAG, "FPL|config|context acquired=" + cachedContext.getClass().getName());
        }
        return cachedContext;
    }

    private static Context fromActivityThreadMethod() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Context fromActivityThreadField() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            java.lang.reflect.Field field = activityThread.getDeclaredField("mSystemContext");
            field.setAccessible(true);
            return (Context) field.get(thread);
        } catch (Throwable failure) {
            return null;
        }
    }

    // ---------------------------------------------------------------- gesture handling

    private static void handle(XC_MethodHook.MethodHookParam param, KeyEvent event) {
        try {
            if (!isPenButton(event)) return;
            // 透传保留实体 deviceId，因此不能靠“没有设备”排除重入。
            // InputDispatcher 会标记注入事件；只放行这一路，避免再次识别和 OEM 抢键。
            if ((((Integer) param.args[1]) & POLICY_FLAG_INJECTED) != 0) {
                param.setResult(1);
                return;
            }
            String button = PenConfig.buttonFor(event.getKeyCode());
            if (button == null) return;

            int code = event.getKeyCode();
            synchronized (STATES) {
                KeyState state = STATES.get(code);
                if (state == null) {
                    state = new KeyState();
                    STATES.put(code, state);
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    // Repeat events and their UP belong to the original DOWN, not a new profile.
                    if (state.down) {
                        if (state.owned) {
                            param.setResult(0);
                            if (!state.cancelled) {
                                if (state.forwarding) ActionRunner.forwardPenEvent(systemContext(), event);
                                else if (!state.longFired) state.events.add(new KeyEvent(event));
                            }
                        }
                        return;
                    }
                    if (event.getRepeatCount() > 0) return;
                    PenConfig current = config.resolveForPackage(focusedPackage);
                    boolean second = state.shortTask != null && state.focusEpoch == focusEpoch
                            && Objects.equals(state.packageName, focusedPackage);
                    // A double click uses the first press's snapshot even if the UI saved meanwhile.
                    if (second) current = state.gestureConfig;
                    else cancelTasks(state);
                    if (!current.owns(button)) return;
                    if (!second) {
                        state.gestureConfig = current;
                        state.packageName = focusedPackage;
                        state.focusEpoch = focusEpoch;
                    }
                    onDown(param, state, button, event, current);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (!state.down || !state.owned) return;
                    onUp(param, state, button, event, state.gestureConfig);
                }
            }
        } catch (Throwable failure) {
            synchronized (STATES) {
                KeyState state = STATES.get(event.getKeyCode());
                if (Integer.valueOf(0).equals(param.getResult()) && state != null) {
                    cancelFailedGesture(state);
                }
            }
            Log.w(TAG, "FPL|controller|gesture failed", failure);
        }
    }

    private static void onDown(XC_MethodHook.MethodHookParam param, final KeyState state,
                               final String button, KeyEvent event, final PenConfig current) {
        // From here on this button belongs to us.
        param.setResult(0);

        if (state.shortTask != null) {
            // Second press of a double click lands inside the short-press window.
            handler.removeCallbacks(state.shortTask);
            state.shortTask = null;
            state.taskToken++;
            state.secondPress = true;
        } else {
            state.secondPress = false;
        }
        state.down = true;
        state.owned = true;
        state.cancelled = false;
        state.longFired = false;
        state.pressDownTime = event.getDownTime();
        state.pressEvent = new KeyEvent(event);
        state.events.add(new KeyEvent(event));
        if (current.passthroughOnly(button)) {
            state.forwarding = true;
            forwardBuffered(state);
            return;
        }
        final long token = ++state.taskToken;
        state.longTask = () -> {
            synchronized (STATES) {
                if (token != state.taskToken || state.focusEpoch != focusEpoch
                        || state.cancelled || !state.down || state.longFired) return;
                state.longFired = true;
                if (state.secondPress) {
                    // 第二次按住已识别为长按，首次短按不能再混入这次长按的透传。
                    state.events.removeIf(buffered -> buffered.getDownTime() != state.pressDownTime);
                }
                Log.i(TAG, "FPL|gesture|" + button + "|LONG");
                runGesture(state, current.action(button, PenConfig.LONG));
            }
        };
        handler.postDelayed(state.longTask, current.longPressMs);
    }

    private static void onUp(XC_MethodHook.MethodHookParam param, final KeyState state,
                             final String button, final KeyEvent event, final PenConfig current) {
        param.setResult(0);

        // A focus/window transition can synthesize a canceled UP before the physical key is
        // released. Keep the logical press alive until that real UP arrives; otherwise the next
        // repeat generated for the still-held hardware key is mistaken for a fresh native event
        // and leaks into the new foreground window.
        if (event.isCanceled() && state.cancelled && !state.forwarding) {
            state.longFired = false;
            state.secondPress = false;
            state.events.clear();
            if (state.longTask != null) {
                handler.removeCallbacks(state.longTask);
                state.longTask = null;
                state.taskToken++;
            }
            return;
        }

        // A user/system cancellation that is not part of a focus transition still terminates the
        // current gesture. Do not let it fall through to the delayed short-press path.
        if (event.isCanceled() && !state.forwarding) {
            state.down = false;
            state.owned = false;
            state.cancelled = false;
            state.longFired = false;
            state.secondPress = false;
            cancelTasks(state);
            return;
        }

        state.down = false;
        state.owned = false;
        if (state.longTask != null) {
            handler.removeCallbacks(state.longTask);
            state.longTask = null;
            state.taskToken++;
        }
        if (state.forwarding) {
            ActionRunner.forwardPenEvent(systemContext(), event);
            state.forwarding = false;
            state.events.clear();
            state.longFired = false;
            state.secondPress = false;
            return;
        }
        if (state.cancelled || state.focusEpoch != focusEpoch) {
            state.events.clear();
            cancelTasks(state);
            state.cancelled = false;
            state.longFired = false;
            state.secondPress = false;
            return;
        }
        if (state.longFired) {
            state.events.clear();
            state.longFired = false;
            state.secondPress = false;
            return;
        }
        state.events.add(new KeyEvent(event));
        if (state.secondPress) {
            state.secondPress = false;
            final long epoch = state.focusEpoch;
            final long doubleToken = ++state.taskToken;
            handler.post(() -> {
                synchronized (STATES) {
                    if (epoch != focusEpoch || doubleToken != state.taskToken) return;
                    Log.i(TAG, "FPL|gesture|" + button + "|DOUBLE");
                    runGesture(state, current.action(button, PenConfig.DOUBLE));
                }
            });
            return;
        }
        final long token = ++state.taskToken;
        state.shortTask = () -> {
            synchronized (STATES) {
                if (token != state.taskToken || state.focusEpoch != focusEpoch) return;
                state.shortTask = null;
                Log.i(TAG, "FPL|gesture|" + button + "|SHORT");
                runGesture(state, current.action(button, PenConfig.SHORT));
            }
        };
        handler.postDelayed(state.shortTask, current.doubleWindowMs);
    }

    private static void cancelTasks(KeyState state) {
        state.taskToken++;
        if (state.longTask != null) handler.removeCallbacks(state.longTask);
        if (state.shortTask != null) handler.removeCallbacks(state.shortTask);
        state.longTask = null;
        state.shortTask = null;
        state.events.clear();
    }

    private static void forwardBuffered(KeyState state) {
        for (KeyEvent event : state.events) ActionRunner.forwardPenEvent(systemContext(), event);
        state.events.clear();
    }

    private static void runGesture(KeyState state, String action) {
        try {
            if (PenConfig.ACTION_PASSTHROUGH.equals(action)) {
                state.forwarding = state.down;
                forwardBuffered(state);
            } else {
                int code = state.events.isEmpty() ? 0 : state.events.get(0).getKeyCode();
                state.events.clear();
                ActionRunner.run(systemContext(), action, code);
            }
        } catch (Throwable failure) {
            // 手势定时器运行于 system_server 主线程，发送失败不能逃出回调。
            cancelFailedGesture(state);
            Log.w(TAG, "FPL|gesture|FAILED " + action, failure);
        }
    }

    private static void cancelFailedGesture(KeyState state) {
        finishForwardedPress(state);
        cancelTasks(state);
        state.forwarding = false;
        state.longFired = false;
        state.secondPress = false;
        state.cancelled = state.down && state.owned;
    }

    private static void finishForwardedPress(KeyState state) {
        if (!state.forwarding || state.pressEvent == null) return;
        int deviceId = state.pressEvent.getDeviceId();
        try {
            // 窗口的 CANCELLED UP 不会清除 InputDispatcher 的自动重复状态。
            ActionRunner.cancelPenEvent(systemContext(), state.pressEvent);
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|controller|cancel input failed", failure);
        } finally {
            // The physical key-down is tracked inside InputDispatcher, outside the policy hook.
            // Reset the pen device so a focus change cannot leave a synthetic repeat running.
            ActionRunner.resetPenDevice(systemContext(), deviceId);
        }
    }

    /** 只识别小米笔输入设备的两个按键；注入事件在策略标志分支单独放行。 */
    private static boolean isPenButton(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();
        if (PenConfig.buttonFor(code) == null) return false;
        InputDevice device = event.getDevice();
        return device != null && PEN_DEVICE.equals(device.getName());
    }
}
