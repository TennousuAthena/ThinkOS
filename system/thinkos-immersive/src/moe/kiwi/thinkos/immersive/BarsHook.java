package moe.kiwi.thinkos.immersive;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Suppress ThinkOS's transient bars, and all applications' bars in the negotiated ADR-046 global mode.
 * Shell protocol 2, APK metadata 2, overlay permission and both native edge hooks must be ready;
 * legacy/missing components leave Android's exits available. Unknown ROM fields fail open.
 * Physical left/bottom callbacks are handled separately, never through a plugin input API.
 */
public class BarsHook implements IXposedHookLoadPackage {
    static final String TAG = "ThinkOS-immersive";
    static final String KIOSK_PACKAGE = "moe.kiwi.thinkos";
    static final String POLICY_CLASS = "com.android.server.policy.PhoneWindowManager";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // The window manager lives in the system server, whose "package" is `android`.
        if (!"android".equals(lpparam.packageName)) return;
        try {
            Class<?> policy = XposedHelpers.findClass(POLICY_CLASS, lpparam.classLoader);
            int hooked = XposedBridge.hookAllMethods(policy, "requestTransientBars", new SwipeGuard()).size();
            int visibility = XposedBridge.hookAllMethods(policy, "updateSystemBarsLw", new GlobalBarFlags()).size();
            GlobalMode.barsReady = hooked > 0 && visibility > 0;
            XposedBridge.log(TAG + ": hooked " + hooked + " requestTransientBars method(s)" + (hooked == 0 ? "; the bars are not suppressed" : ""));
            XposedBridge.log(TAG + ": hooked " + visibility + " updateSystemBarsLw method(s); protocol 2");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": not hooked: " + error);
        }
        BottomSwipeHook.install(lpparam.classLoader);
    }

    /** Whether an edge swipe should do nothing now, given the package that owns the focused window (null: nobody is focused). */
    static boolean swallows(String focusedPackage) {
        return KIOSK_PACKAGE.equals(focusedPackage);
    }

    static final class SwipeGuard extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object focused = XposedHelpers.getObjectField(param.thisObject, "mFocusedWindow");
                if (focused == null) return;
                String owner = (String) XposedHelpers.callMethod(focused, "getOwningPackage");
                // A failed fixed Back must restore Android's escape route
                // even above a ThinkOS-owned native dialog. The legacy own-
                // window suppression is safe only while that bridge is healthy.
                if (!BackSwipeHook.broken && swallows(owner) || owner != null && !owner.isEmpty() && GlobalMode.active(
                        (android.content.Context) XposedHelpers.getObjectField(param.thisObject, "mContext"))) param.setResult(null);
            } catch (Throwable ignored) {
                // Not knowing who has the focus is no reason to change what the system does.
            }
        }
    }

    /** API27 can clear HIDE_NAVIGATION while an IME is visible, without asking
     * for transient bars. Keep the negotiated global shell's flags at both
     * sides of the policy calculation, including its transient request bits.
     * These are Android 8.1 protocol constants, not user timing/settings. */
    static final class GlobalBarFlags extends XC_MethodHook {
        private static final int HIDDEN = 0x1006; // IMMERSIVE_STICKY | FULLSCREEN | HIDE_NAVIGATION
        private static final int TRANSIENT = 0x0c000000; // STATUS_BAR_TRANSIENT | NAVIGATION_BAR_TRANSIENT
        private static int hidden(int flags) { return (flags | HIDDEN) & ~TRANSIENT; }
        private static boolean active(MethodHookParam param) {
            try { return GlobalMode.active((android.content.Context)XposedHelpers.getObjectField(param.thisObject,"mContext")); }
            catch (Throwable ignored) { return false; }
        }
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args!=null && param.args.length==3 && param.args[2] instanceof Integer && active(param))
                param.args[2]=hidden((Integer)param.args[2]);
        }
        @Override protected void afterHookedMethod(MethodHookParam param) {
            if (param.getResult() instanceof Integer && active(param)) param.setResult(hidden((Integer)param.getResult()));
        }
    }
}
