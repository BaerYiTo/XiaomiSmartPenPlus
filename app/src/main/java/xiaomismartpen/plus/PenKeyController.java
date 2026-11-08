package xiaomismartpen.plus;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Owns the two pen buttons and replaces the ROM's actions with the configured ones.
 *
 * Hook point: com.android.server.policy.MiuiPhoneWindowManager#interceptKeyBeforeQueueing(KeyEvent,int)
 *   return 1 = the event continues to the user, return 0 = the event is dropped.
 * That method is the ROM's own gate: it calls MiuiStylusTouchFilmManager and
 * PhoneWindowManagerStubImpl (which posts the 400 ms long-press runnable), so returning 0 from the
 * before-hook suppresses the native quick-note mask and partial screenshot too. Measured on the
 * device: button 1 = KEYCODE_PAGE_DOWN, button 2 = KEYCODE_PAGE_UP, native long press = 400 ms.
 *
 * Safety rules, all load-bearing:
 *  - a button is only touched when the user enabled takeover for it AND gave it at least one action
 *    ({@link PenConfig#owns}); otherwise the native path runs untouched;
 *  - the config is read on a background thread and cached, never over IPC on the input thread;
 *  - any exception leaves the event's result untouched, so it behaves as if the module were absent.
 */
final class PenKeyController {
    private static final String TAG = "FocusPenLite";

    static final int KEY_BUTTON_1 = KeyEvent.KEYCODE_PAGE_DOWN;
    static final int KEY_BUTTON_2 = KeyEvent.KEYCODE_PAGE_UP;
    private static final String PEN_DEVICE = "Xiaomi Smart Pen Keyboard";

    private static boolean installed;
    private static Handler handler;
    private static Handler configHandler;
    private static volatile PenConfig config = PenConfig.defaults();
    private static volatile long configLoadedAt;
    private static final long CONFIG_RETRY_MS = 2000L;
    private static final long CONFIG_REFRESH_MS = 5000L;
    /** Observer lifecycle and the single polling chain are confined to configHandler. */
    private static boolean observerRegistered;
    private static ContentObserver configObserver;

    private static final Map<Integer, KeyState> STATES = new HashMap<>();

    private PenKeyController() { }

    /** Per-button gesture state. */
    private static final class KeyState {
        boolean down;
        boolean owned;
        boolean cancelled;
        boolean longFired;
        boolean secondPress;
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

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.policy.MiuiPhoneWindowManager", loader,
                    "interceptKeyBeforeQueueing", KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            handle(param, (KeyEvent) param.args[0]);
                        }
                    });
            Log.i(TAG, "FPL|controller|installed");
            XposedBridge.log(TAG + " controller installed");
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|controller|MISSING " + failure.getClass().getSimpleName());
            XposedBridge.log(TAG + " controller missing: " + failure);
        }
        installFocusTracking(loader);
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
                cancelTasks(state);
                // An intercepted DOWN still needs its UP swallowed, even after the app changes.
                state.cancelled = state.down && state.owned;
                state.secondPress = false;
            }
        }
        Log.i(TAG, "FPL|focus|" + pkg);
    }

    private static void installFocusTracking(ClassLoader loader) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.miui.server.input.stylus.MiuiStylusShortcutManager", loader);
            // Signature differs between ROMs, so match by name rather than by parameter list.
            XposedBridge.hookAllMethods(manager, "onDefaultDisplayFocusChangedLw", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    updateFocus(param.args.length == 0 ? null : packageOf(param.args[0]));
                }
            });
            Log.i(TAG, "FPL|focus|tracking installed");
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|focus|tracking MISSING " + failure.getClass().getSimpleName());
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
        final Context context = systemContext();
        boolean loaded = false;
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
        configHandler.postDelayed(PenKeyController::watchConfig,
                loaded && observerRegistered ? CONFIG_REFRESH_MS : CONFIG_RETRY_MS);
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
            if (raw == null) {
                Log.w(TAG, "FPL|config|empty snapshot");
                return false;
            }
            PenConfig loaded = PenConfig.parse(raw);
            config = loaded;
            configLoadedAt = android.os.SystemClock.uptimeMillis();
            Log.i(TAG, "FPL|config|loaded b1=" + loaded.owns(PenConfig.B1)
                    + " b2=" + loaded.owns(PenConfig.B2)
                    + " apps=" + loaded.appPackages().size()
                    + " long=" + loaded.longPressMs + " double=" + loaded.doubleWindowMs);
            return true;
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|config|reload failed: " + failure.getClass().getSimpleName());
            return false;
        }
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
        if (!isPenButton(event)) return;
        try {
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
                        if (state.owned) param.setResult(0);
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
                    onUp(param, state, button, event.getKeyCode(), state.gestureConfig,
                            event.isCanceled());
                }
            }
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|controller|passthrough on error", failure);
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
        if (!state.secondPress) {
            final long token = ++state.taskToken;
            state.longTask = () -> {
                synchronized (STATES) {
                    if (token != state.taskToken || state.focusEpoch != focusEpoch
                            || state.cancelled || !state.down || state.longFired) return;
                    state.longFired = true;
                    Log.i(TAG, "FPL|gesture|" + button + "|LONG");
                    ActionRunner.run(systemContext(), current.action(button, PenConfig.LONG), event.getKeyCode());
                }
            };
            handler.postDelayed(state.longTask, current.longPressMs);
        }
    }

    private static void onUp(XC_MethodHook.MethodHookParam param, final KeyState state,
                             final String button, final int code, final PenConfig current,
                             boolean eventCancelled) {
        param.setResult(0);
        state.down = false;
        state.owned = false;
        if (state.longTask != null) {
            handler.removeCallbacks(state.longTask);
            state.longTask = null;
            state.taskToken++;
        }
        if (state.cancelled || eventCancelled || state.focusEpoch != focusEpoch) {
            cancelTasks(state);
            state.cancelled = false;
            state.longFired = false;
            state.secondPress = false;
            return;
        }
        if (state.longFired) {
            state.longFired = false;
            state.secondPress = false;
            return;
        }
        if (state.secondPress) {
            state.secondPress = false;
            final long epoch = state.focusEpoch;
            handler.post(() -> {
                synchronized (STATES) {
                    if (epoch != focusEpoch) return;
                    Log.i(TAG, "FPL|gesture|" + button + "|DOUBLE");
                    ActionRunner.run(systemContext(), current.action(button, PenConfig.DOUBLE), code);
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
                ActionRunner.run(systemContext(), current.action(button, PenConfig.SHORT), code);
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
    }

    /** Only the two pen buttons on their own input device; injected events carry no device. */
    private static boolean isPenButton(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();
        if (code != KEY_BUTTON_1 && code != KEY_BUTTON_2) return false;
        InputDevice device = event.getDevice();
        return device != null && PEN_DEVICE.equals(device.getName());
    }
}
