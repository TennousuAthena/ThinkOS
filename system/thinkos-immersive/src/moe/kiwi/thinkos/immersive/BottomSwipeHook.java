package moe.kiwi.thinkos.immersive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Process;
import java.util.HashSet;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Native bottom callback sends private Home; native left callback emits fixed Back in global mode. */
final class BottomSwipeHook {
    static final String LISTENER = "com.android.server.policy.SystemGesturesPointerEventListener";
    private static final Set<Class<?>> hookedCallbacks = new HashSet<>();

    private BottomSwipeHook() { }

    static void install(ClassLoader loader) {
        try {
            Class<?> listener = XposedHelpers.findClass(LISTENER, loader);
            int count = XposedBridge.hookAllConstructors(listener, new CallbackInstaller()).size();
            XposedBridge.log(BarsHook.TAG + ": hooked " + count + " gesture listener constructor(s)");
        } catch (Throwable error) {
            XposedBridge.log(BarsHook.TAG + ": bottom gesture not hooked: " + error);
        }
    }

    /** Physical direction is independent of the navigation bar's location and the app's reading direction. */
    static boolean shouldNotify(String focusedPackage) {
        return focusedPackage != null && !focusedPackage.isEmpty()
            && !BarsHook.KIOSK_PACKAGE.equals(focusedPackage)
            && !"android".equals(focusedPackage) && !"com.android.systemui".equals(focusedPackage);
    }

    static final class CallbackInstaller extends XC_MethodHook {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object callbacks = XposedHelpers.getObjectField(param.thisObject, "mCallbacks");
                if (callbacks == null) return;
                Class<?> type = callbacks.getClass();
                synchronized (hookedCallbacks) {
                    if (hookedCallbacks.contains(type)) return;
                    int count = XposedBridge.hookAllMethods(type, "onSwipeFromBottom", new Sender()).size();
                    int back = XposedBridge.hookAllMethods(type, "onSwipeFromLeft", new BackSwipeHook(param.thisObject)).size();
                    GlobalMode.callbacksReady = count > 0 && back > 0;
                    if (count > 0) {
                        hookedCallbacks.add(type);
                        XposedBridge.log(BarsHook.TAG + ": hooked onSwipeFromBottom (" + count + ")");
                        XposedBridge.log(BarsHook.TAG + ": hooked onSwipeFromLeft (" + back + ")");
                    }
                }
            } catch (Throwable ignored) {
                // A ROM with different callbacks simply has no external return gesture.
            }
        }
    }

    static final class Sender extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final Object policy = XposedHelpers.getObjectField(param.thisObject, "this$0");
                Object focused = XposedHelpers.getObjectField(policy, "mFocusedWindow");
                final Context context = (Context) XposedHelpers.getObjectField(policy, "mContext");
                if (focused == null) return;
                String owner = (String) XposedHelpers.callMethod(focused, "getOwningPackage");
                boolean global = GlobalMode.active(context);
                if (owner == null || owner.isEmpty() || (!global && !shouldNotify(owner))) return;
                Handler handler = (Handler) XposedHelpers.getObjectField(policy, "mHandler");
                if (context == null || handler == null) return;
                handler.post(new Runnable() {
                    @Override public void run() {
                        try {
                            Intent intent = new Intent().setComponent(new ComponentName(BarsHook.KIOSK_PACKAGE,
                                "moe.kiwi.thinkos.shell.EdgeReturnReceiver")).addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                            context.sendBroadcastAsUser(intent, Process.myUserHandle());
                        } catch (Throwable ignored) {
                            // An absent/older APK must never throw back into system_server's handler.
                        }
                    }
                });
            } catch (Throwable ignored) {
                // Missing policy/window fields are not a reason to disturb Android's own gesture.
            }
        }
    }
}
