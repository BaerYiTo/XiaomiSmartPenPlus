package xiaomismartpen.plus;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Every action the module can perform. Each one reports failure in the log instead of swallowing
 * it, so a broken action is visible on the device rather than silently doing nothing.
 */
final class ActionRunner {
    private static final String TAG = "FocusPenLite";

    /** system_server's own loader; the module's loader cannot see com.miui.server.* classes. */
    private static volatile ClassLoader systemLoader;
    private static volatile Object nativeInput;
    private static final Handler RESET_HANDLER = new Handler(Looper.getMainLooper());
    private static final int POLICY_FLAG_FILTERED = 0x04000000;
    private static final int POLICY_FLAG_PASS_TO_USER = 0x40000000;

    private ActionRunner() { }

    static void setSystemClassLoader(ClassLoader loader) {
        systemLoader = loader;
    }

    static void run(Context context, String action, int penKeyCode) {
        if (action == null || PenConfig.ACTION_NONE.equals(action)) return;
        Log.i(TAG, "FPL|action|" + action);
        try {
            if (action.startsWith(PenConfig.ACTION_LAUNCH_PREFIX)) {
                launch(context, action.substring(PenConfig.ACTION_LAUNCH_PREFIX.length()));
                return;
            }
            switch (action) {
                case PenConfig.ACTION_VOLUME_UP:
                    volume(context, AudioManager.ADJUST_RAISE);
                    break;
                case PenConfig.ACTION_VOLUME_DOWN:
                    volume(context, AudioManager.ADJUST_LOWER);
                    break;
                case PenConfig.ACTION_PLAY_PAUSE:
                    inject(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    break;
                case PenConfig.ACTION_BACK:
                    inject(context, KeyEvent.KEYCODE_BACK);
                    break;
                case PenConfig.ACTION_HOME:
                    inject(context, KeyEvent.KEYCODE_HOME);
                    break;
                case PenConfig.ACTION_RECENTS:
                    inject(context, KeyEvent.KEYCODE_APP_SWITCH);
                    break;
                case PenConfig.ACTION_PAGE_UP:
                    inject(context, KeyEvent.KEYCODE_PAGE_UP);
                    break;
                case PenConfig.ACTION_PAGE_DOWN:
                    inject(context, KeyEvent.KEYCODE_PAGE_DOWN);
                    break;
                case PenConfig.ACTION_SWIPE_UP:
                    swipe(context, true);
                    break;
                case PenConfig.ACTION_SWIPE_DOWN:
                    swipe(context, false);
                    break;
                case PenConfig.ACTION_SWIPE_LEFT:
                    swipeHorizontally(context, true);
                    break;
                case PenConfig.ACTION_SWIPE_RIGHT:
                    swipeHorizontally(context, false);
                    break;
                case PenConfig.ACTION_SCREENSHOT:
                    screenshot(context);
                    break;
                case PenConfig.ACTION_PASSTHROUGH:
                    throw new IllegalStateException("Pen passthrough requires the original event");
                default:
                    Log.w(TAG, "FPL|action|unknown " + action);
                    break;
            }
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|action|FAILED " + action, failure);
        }
    }

    private static void volume(Context context, int direction) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    private static void inject(Context context, int keyCode) {
        InputManager input = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        if (input == null) return;
        long now = SystemClock.uptimeMillis();
        send(input, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        send(input, new KeyEvent(now, now + 5, KeyEvent.ACTION_UP, keyCode, 0));
    }

    /** 恢复小米笔的标准翻页键，并保留设备、扫描码、重复事件及原始按压时间。 */
    static void forwardPenEvent(Context context, KeyEvent original) {
        sendPenEvent(context, original, POLICY_FLAG_FILTERED | POLICY_FLAG_PASS_TO_USER);
    }

    /** 结束已离开焦点的透传按压，仅清理 native 持键状态，不向新窗口发送普通 UP。 */
    static void cancelPenEvent(Context context, KeyEvent down) {
        KeyEvent release = new KeyEvent(down.getDownTime(), SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, down.getKeyCode(), 0, down.getMetaState(), down.getDeviceId(),
                down.getScanCode(), down.getFlags() | KeyEvent.FLAG_CANCELED, down.getSource());
        de.robv.android.xposed.XposedHelpers.callMethod(release, "setDisplayId",
                de.robv.android.xposed.XposedHelpers.callMethod(down, "getDisplayId"));
        sendPenEvent(context, release, POLICY_FLAG_FILTERED);
    }

    /**
     * Injected CANCEL/UP events are delivered to windows, but they do not pass through
     * InputDispatcher.notifyKey(), so the dispatcher can keep synthesizing repeats for the
     * original physical device. Reset the pen device to clear that native repeat state, then
     * bring it back online after the reset has propagated.
     */
    static void resetPenDevice(Context context, int deviceId) {
        if (context == null || deviceId < 0) return;
        try {
            InputManager input = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (input == null) return;
            java.lang.reflect.Method disable = InputManager.class.getMethod("disableInputDevice", int.class);
            java.lang.reflect.Method enable = InputManager.class.getMethod("enableInputDevice", int.class);
            disable.invoke(input, deviceId);
            Log.i(TAG, "FPL|input|reset device=" + deviceId);
            RESET_HANDLER.postDelayed(() -> {
                try {
                    enable.invoke(input, deviceId);
                    Log.i(TAG, "FPL|input|reset complete device=" + deviceId);
                } catch (Throwable failure) {
                    Log.w(TAG, "FPL|input|re-enable failed device=" + deviceId, failure);
                }
            }, 60L);
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|input|reset failed device=" + deviceId, failure);
        }
    }

    private static void sendPenEvent(Context context, KeyEvent original, int policyFlags) {
        if (context == null) throw new IllegalStateException("System context unavailable");
        int code = original.getKeyCode();
        if (code == PenKeyController.KEY_COLOROS_LOWER) code = KeyEvent.KEYCODE_PAGE_DOWN;
        else if (code == PenKeyController.KEY_COLOROS_UPPER) code = KeyEvent.KEYCODE_PAGE_UP;
        KeyEvent forwarded = new KeyEvent(original.getDownTime(), original.getEventTime(),
                original.getAction(), code, original.getRepeatCount(), original.getMetaState(),
                original.getDeviceId(), original.getScanCode(),
                original.getFlags() | KeyEvent.FLAG_KEEP_TOUCH_MODE, original.getSource());
        // PAGE_UP/DOWN 是导航键；ViewRootImpl 会用首次 DOWN 退出触摸模式并抢焦点。
        // 笔按钮应留在当前画布，让应用收到 DOWN，而不是执行键盘焦点导航。
        // 构造器不含 displayId；按当前系统的 KeyEvent 实现复制显示目标。
        de.robv.android.xposed.XposedHelpers.callMethod(forwarded, "setDisplayId",
                de.robv.android.xposed.XposedHelpers.callMethod(original, "getDisplayId"));
        // 常规 injectInputEvent 在 InputDispatcher 内强制使用虚拟设备 ID。
        // 与 InputFilterHost 使用同一个 native 入口和 FILTERED 标志，保留原始设备身份。
        Object accepted = de.robv.android.xposed.XposedHelpers.callMethod(nativeInput(),
                "injectInputEvent", forwarded, false, -1, 0, 0,
                policyFlags);
        if (!(accepted instanceof Integer) || ((Integer) accepted) != 0) {
            throw new IllegalStateException("Pen input rejected: " + accepted);
        }
    }

    private static Object nativeInput() {
        if (nativeInput != null) return nativeInput;
        Class<?> internal = de.robv.android.xposed.XposedHelpers.findClass(
                "com.android.server.input.InputManagerInternal", systemLoader);
        Class<?> services = de.robv.android.xposed.XposedHelpers.findClass(
                "com.android.server.LocalServices", systemLoader);
        Object local = de.robv.android.xposed.XposedHelpers.callStaticMethod(services, "getService", internal);
        if (local == null) throw new IllegalStateException("Input local service unavailable");
        Object manager = de.robv.android.xposed.XposedHelpers.getObjectField(local, "this$0");
        nativeInput = de.robv.android.xposed.XposedHelpers.getObjectField(manager, "mNative");
        if (nativeInput == null) throw new IllegalStateException("Native input service unavailable");
        return nativeInput;
    }

    /** InputManager.injectInputEvent(InputEvent,int) is @hide; mode 0 = ASYNC. */
    private static void send(InputManager input, InputEvent event) {
        try {
            Object accepted = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class)
                    .invoke(input, event, 0);
            if (Boolean.FALSE.equals(accepted)) throw new IllegalStateException("Input injection rejected");
        } catch (Throwable failure) {
            throw new IllegalStateException("Input injection failed", failure);
        } finally {
            if (event instanceof MotionEvent) ((MotionEvent) event).recycle();
        }
    }

    /**
     * A vertical swipe across the middle of the screen. Feed apps only navigate on touch: a page key
     * reaches them and is dropped, while a gesture is exactly what they handle. Timestamps are laid
     * out backwards from now, so the whole gesture is injected without blocking the input thread,
     * yet the app still computes a realistic fling velocity from the event times.
     */
    private static void swipe(Context context, boolean upward) {
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        float x = metrics.widthPixels * 0.5f;
        float top = metrics.heightPixels * 0.28f;
        float bottom = metrics.heightPixels * 0.72f;
        float from = upward ? bottom : top;
        float to = upward ? top : bottom;
        injectSwipe(context, x, from, x, to);
    }

    private static void swipeHorizontally(Context context, boolean leftward) {
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        float y = metrics.heightPixels * 0.5f;
        float left = metrics.widthPixels * 0.25f;
        float right = metrics.widthPixels * 0.75f;
        injectSwipe(context, leftward ? right : left, y, leftward ? left : right, y);
    }

    private static void injectSwipe(Context context, float x0, float y0, float x1, float y1) {
        InputManager input = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        if (input == null) return;

        final long durationMs = 160L;
        final int steps = 8;
        long now = SystemClock.uptimeMillis();
        long downTime = now - durationMs;

        send(input, motion(downTime, downTime, MotionEvent.ACTION_DOWN, x0, y0));
        for (int step = 1; step <= steps; step++) {
            send(input, motion(downTime, downTime + durationMs * step / steps,
                    MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * step / steps,
                    y0 + (y1 - y0) * step / steps));
        }
        send(input, motion(downTime, now, MotionEvent.ACTION_UP, x1, y1));
    }

    private static MotionEvent motion(long downTime, long eventTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return event;
    }

    /** 按系统调用原生区域截图，不以全屏截图替代。 */
    private static void screenshot(Context context) throws ReflectiveOperationException {
        if (RomProfile.detect() == RomProfile.Family.COLOROS) {
            ColorScreenshot.run(context, systemLoader);
            return;
        }
        if (RomProfile.detect() != RomProfile.Family.HYPEROS) {
            throw new IllegalStateException("Partial screenshot is unavailable for this ROM");
        }
        Class<?> shortcut = Class.forName("com.miui.server.input.util.ShortCutActionsUtils",
                false, systemLoader);
        Object instance = shortcut.getMethod("getInstance", Context.class).invoke(null, context);
        Object result = shortcut.getMethod("triggerFunction", String.class, String.class,
                        android.os.Bundle.class, boolean.class)
                .invoke(instance, "stylus_partial_screenshot", "long_press_page_up_key", null, false);
        if (Boolean.FALSE.equals(result)) {
            throw new IllegalStateException("MIUI partial screenshot rejected");
        }
        Log.i(TAG, "FPL|action|screenshot result=" + result);
    }

    private static void launch(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Log.w(TAG, "FPL|action|launch no entry for " + pkg);
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
