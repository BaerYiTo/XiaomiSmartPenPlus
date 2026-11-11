package xiaomismartpen.plus;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.input.InputManager;
import android.media.AudioManager;
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
                    inject(context, penKeyCode);
                    break;
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

    /** InputManager.injectInputEvent(InputEvent,int) is @hide; mode 0 = ASYNC. */
    private static void send(InputManager input, InputEvent event) {
        try {
            InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class)
                    .invoke(input, event, 0);
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|action|inject FAILED", failure);
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

    /**
     * A horizontal swipe across the middle of the screen, for novel readers that page left/right:
     * moving the finger toward the left goes to the next page, toward the right goes back one page.
     * Same synthetic-touch trick as {@link #swipe(Context, boolean)}.
     */
    private static void swipeHorizontally(Context context, boolean leftward) {
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        float y = metrics.heightPixels * 0.5f;
        float left = metrics.widthPixels * 0.25f;
        float right = metrics.widthPixels * 0.75f;
        float from = leftward ? right : left;
        float to = leftward ? left : right;
        injectSwipe(context, from, y, to, y);
    }

    /**
     * Inject one straight drag from (x0, y0) to (x1, y1). Timestamps are laid out backwards from
     * now, so the whole gesture is injected without blocking the input thread, yet the app still
     * computes a realistic fling velocity from the event times.
     */
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

    /** Partial screenshot through the ROM's own entry point, which is what the pen long press used. */
    private static void screenshot(Context context) {
        try {
            Class<?> cls = Class.forName("com.miui.server.input.util.ShortCutActionsUtils",
                    false, systemLoader);
            Object instance = cls.getMethod("getInstance", Context.class).invoke(null, context);
            Object result = cls.getMethod("triggerFunction", String.class, String.class,
                            android.os.Bundle.class, boolean.class)
                    .invoke(instance, "stylus_partial_screenshot", "long_press_page_up_key",
                            null, false);
            Log.i(TAG, "FPL|action|screenshot result=" + result);
        } catch (Throwable failure) {
            Log.w(TAG, "FPL|action|screenshot FAILED", failure);
        }
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
