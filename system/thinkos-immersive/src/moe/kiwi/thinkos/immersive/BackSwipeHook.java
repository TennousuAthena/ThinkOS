package moe.kiwi.thinkos.immersive;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.graphics.Region;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Fixed Back from the framework's real left-edge callback. No application request endpoint. */
final class BackSwipeHook extends XC_MethodHook {
    static volatile boolean broken;
    private final Object gestures;
    BackSwipeHook() { this(null); }
    BackSwipeHook(Object gestures) { this.gestures=gestures; }
    @Override protected void beforeHookedMethod(MethodHookParam param) {
        try {
            final Object policy = XposedHelpers.getObjectField(param.thisObject, "this$0");
            final Object focused = XposedHelpers.getObjectField(policy, "mFocusedWindow");
            if (focused == null) return;
            final Context context = (Context) XposedHelpers.getObjectField(policy, "mContext");
            Handler handler = (Handler) XposedHelpers.getObjectField(policy, "mHandler");
            if (handler == null || !GlobalMode.active(context)) return;
            String owner = (String) XposedHelpers.callMethod(focused, "getOwningPackage");
            if (owner == null || owner.isEmpty() || ownsGesture(focused, owner) || !GlobalMode.active(context)) return;
            handler.post(new Runnable() {
                @Override public void run() {
                    try {
                        // Do not send a delayed Back into a different dialog/window.
                        if (XposedHelpers.getObjectField(policy, "mFocusedWindow") != focused || !GlobalMode.active(context) || panelOwnsGesture(focused) || broken) return;
                        injectBack();
                    } catch (Throwable error) {
                        broken = true;
                        XposedBridge.log(BarsHook.TAG + ": global Back unavailable; Android bars restored: " + error.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable ignored) { /* Unknown ROM: leave the original callback alone. */ }
    }

    private boolean ownsGesture(Object focused, String owner) {
        if (panelOwnsGesture(focused)) return true;
        if (!BarsHook.KIOSK_PACKAGE.equals(owner)) return false;
        try {
            Object attrs=XposedHelpers.callMethod(focused,"getAttrs");
            int type=(Integer)XposedHelpers.getObjectField(attrs,"type");
            if (type!=1) return false; // Native dialogs/popups receive the ordinary Back key.
            Object service=XposedHelpers.getObjectField(focused,"mService");
            Object ime=XposedHelpers.getObjectField(service,"mInputMethodWindow");
            if (ime!=null && gestures!=null && Boolean.TRUE.equals(XposedHelpers.callMethod(ime,"isVisibleLw"))) {
                Object handle=XposedHelpers.getObjectField(ime,"mInputWindowHandle");
                Region region=(Region)XposedHelpers.getObjectField(handle,"touchableRegion");
                float[] x=(float[])XposedHelpers.getObjectField(gestures,"mDownX");
                float[] y=(float[])XposedHelpers.getObjectField(gestures,"mDownY");
                if (x.length>0 && y.length>0 && region.contains((int)x[0],(int)y[0])) return false;
            }
        } catch (Throwable ignored) { /* Unknown fields leave the base activity's own gesture intact. */ }
        return true;
    }

    /** The non-focusable panel cannot receive a key. Its own edge handler
     * closes it on UP; do not also navigate the still-focused external app.
     * Read the framework's window records, never an app-provided request. */
    private static boolean panelOwnsGesture(Object focused) {
        try {
            Object service=XposedHelpers.getObjectField(focused,"mService");
            Map<?,?> windows=(Map<?,?>)XposedHelpers.getObjectField(service,"mWindowMap");
            Object[] snapshot;
            synchronized (windows) {
                if (windows.size()>512) throw new IllegalStateException("window limit");
                snapshot=windows.values().toArray();
            }
            for (Object window:snapshot) {
                if (!BarsHook.KIOSK_PACKAGE.equals(XposedHelpers.callMethod(window,"getOwningPackage"))) continue;
                Object attrs=XposedHelpers.callMethod(window,"getAttrs");
                if (!Integer.valueOf(2038).equals(XposedHelpers.getObjectField(attrs,"type")) ||
                    !Integer.valueOf(-1).equals(XposedHelpers.getObjectField(attrs,"width")) ||
                    !Integer.valueOf(-1).equals(XposedHelpers.getObjectField(attrs,"height"))) continue;
                if ("ThinkOS Panel".contentEquals((CharSequence)XposedHelpers.callMethod(attrs,"getTitle")) &&
                    Boolean.TRUE.equals(XposedHelpers.callMethod(window,"isVisibleLw"))) return true;
            }
            return false;
        } catch (Throwable error) {
            broken=true;
            XposedBridge.log(BarsHook.TAG+": global window state unavailable; Android bars restored: "+error.getClass().getSimpleName());
            return false;
        }
    }

    static void injectBack() throws ReflectiveOperationException {
        Class<?> manager = Class.forName("android.hardware.input.InputManager");
        Object input = manager.getMethod("getInstance").invoke(null);
        java.lang.reflect.Method inject = manager.getMethod("injectInputEvent", InputEvent.class, int.class);
        long now = SystemClock.uptimeMillis();
        boolean down = false;
        boolean up;
        try { down = Boolean.TRUE.equals(inject.invoke(input, key(now, KeyEvent.ACTION_DOWN), 0)); }
        finally { up = Boolean.TRUE.equals(inject.invoke(input, key(now, KeyEvent.ACTION_UP), 0)); }
        if (!down || !up) throw new IllegalStateException("Back event rejected");
    }
    private static KeyEvent key(long now, int action) {
        return new KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_KEYBOARD);
    }
}
