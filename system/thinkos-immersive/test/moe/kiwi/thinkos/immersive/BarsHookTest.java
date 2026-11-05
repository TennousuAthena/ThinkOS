package moe.kiwi.thinkos.immersive;

import com.android.server.policy.PhoneWindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Host test for the hook's decisions. Run with system/test/run-immersive.sh. */
public class BarsHookTest {
    static int failed = 0;

    static void check(String name, boolean ok) {
        if (!ok) failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static class Window {
        private final String owner;
        public final Attrs attrs = new Attrs();
        public Service mService = new Service();
        public InputHandle mInputWindowHandle = new InputHandle();
        public boolean visible = true;
        Window(String owner) { this.owner = owner; }
        public String getOwningPackage() { return owner; }
        public Attrs getAttrs() { return attrs; }
        public boolean isVisibleLw() { return visible; }
    }
    static final class Attrs {
        public int type=1, width=-1, height=-1;
        public String title="";
        public CharSequence getTitle() { return title; }
    }
    static final class Service {
        public final java.util.Map<Integer,Window> mWindowMap = new java.util.HashMap<>();
        public Window mInputMethodWindow;
    }
    static final class InputHandle {
        public android.graphics.Region touchableRegion = new android.graphics.Region(0,500,800,1280);
    }

    static class BrokenWindow {
        public String getOwningPackage() { throw new IllegalStateException("no owner"); }
    }

    static XC_MethodHook.MethodHookParam swipeWith(Object focused) {
        PhoneWindowManager policy = new PhoneWindowManager();
        policy.mFocusedWindow = focused;
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.thisObject = policy;
        param.args = new Object[] {new Window("android")};
        return param;
    }

    static XC_LoadPackage.LoadPackageParam load(String packageName) {
        XC_LoadPackage.LoadPackageParam param = new XC_LoadPackage.LoadPackageParam();
        param.packageName = packageName;
        param.processName = packageName;
        param.classLoader = BarsHookTest.class.getClassLoader();
        return param;
    }

    public static void main(String[] args) throws Throwable {
        BarsHook.SwipeGuard guard = new BarsHook.SwipeGuard();

        XC_MethodHook.MethodHookParam thinkos = swipeWith(new Window("moe.kiwi.thinkos"));
        guard.beforeHookedMethod(thinkos);
        check("an edge swipe does nothing while ThinkOS has the focus", thinkos.stubReturnedEarly());

        XC_MethodHook.MethodHookParam settings = swipeWith(new Window("com.android.settings"));
        guard.beforeHookedMethod(settings);
        check("another app keeps the system's behaviour (the bars and Back stay)", !settings.stubReturnedEarly());

        XC_MethodHook.MethodHookParam lookalike = swipeWith(new Window("moe.kiwi.thinkos.test"));
        guard.beforeHookedMethod(lookalike);
        check("only ThinkOS itself, not a package that starts with its name", !lookalike.stubReturnedEarly());

        XC_MethodHook.MethodHookParam nobody = swipeWith(null);
        guard.beforeHookedMethod(nobody);
        check("with no window focused the system is left alone", !nobody.stubReturnedEarly());

        XC_MethodHook.MethodHookParam ownerless = swipeWith(new Window(null));
        guard.beforeHookedMethod(ownerless);
        check("a window without an owner is left alone", !ownerless.stubReturnedEarly());

        XC_MethodHook.MethodHookParam broken = swipeWith(new BrokenWindow());
        boolean threw = false;
        try { guard.beforeHookedMethod(broken); } catch (Throwable error) { threw = true; }
        check("a window that fails to answer neither throws into the system nor skips the call", !threw && !broken.stubReturnedEarly());

        XC_MethodHook.MethodHookParam foreignPolicy = new XC_MethodHook.MethodHookParam();
        foreignPolicy.thisObject = new Object();
        threw = false;
        try { guard.beforeHookedMethod(foreignPolicy); } catch (Throwable error) { threw = true; }
        check("a window policy without the field (another Android version) is left alone", !threw && !foreignPolicy.stubReturnedEarly());

        XposedBridge.stubHooks.clear();
        new BarsHook().handleLoadPackage(load("com.android.settings"));
        check("an ordinary app process is not hooked", XposedBridge.stubHooks.isEmpty());

        new BarsHook().handleLoadPackage(load("android"));
        check("the system server gets both transient and visibility hooks", XposedBridge.stubHooks.size() == 3
            && XposedBridge.stubHooks.get(0)[0] == PhoneWindowManager.class
            && "requestTransientBars".equals(XposedBridge.stubHooks.get(0)[1])
            && XposedBridge.stubHooks.get(0)[2] instanceof BarsHook.SwipeGuard
            && "updateSystemBarsLw".equals(XposedBridge.stubHooks.get(1)[1])
            && XposedBridge.stubHooks.get(1)[2] instanceof BarsHook.GlobalBarFlags);

        XposedBridge.stubLog.clear();
        XC_LoadPackage.LoadPackageParam elsewhere = load("android");
        elsewhere.classLoader = new ClassLoader(null) { };
        new BarsHook().handleLoadPackage(elsewhere);
        check("a system without that class logs and carries on", XposedBridge.stubLog.size() == 2 && XposedBridge.stubLog.get(0).contains("not hooked"));

        System.out.println(failed == 0 ? "\nall checks passed" : "\n" + failed + " checks failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
