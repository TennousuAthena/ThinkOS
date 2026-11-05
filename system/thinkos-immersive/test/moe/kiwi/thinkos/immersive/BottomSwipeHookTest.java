package moe.kiwi.thinkos.immersive;

import android.content.Intent;
import android.os.Process;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.policy.SystemGesturesPointerEventListener;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public final class BottomSwipeHookTest {
    private static int failed;
    private BottomSwipeHookTest() { }
    private static void check(String name, boolean ok) {
        if (!ok) failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }
    private static XC_MethodHook.MethodHookParam param(Object object) {
        XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam(); p.thisObject = object; return p;
    }
    private static PhoneWindowManager policy(String owner) {
        PhoneWindowManager p = new PhoneWindowManager(); p.mFocusedWindow = new BarsHookTest.Window(owner); return p;
    }
    public static void main(String[] args) {
        BottomSwipeHook.Sender sender = new BottomSwipeHook.Sender();
        for (String owner : new String[] {null, "", "moe.kiwi.thinkos", "android", "com.android.systemui"}) {
            PhoneWindowManager p = policy(owner);
            XC_MethodHook.MethodHookParam call = param(p.new SwipeCallbacks()); sender.beforeHookedMethod(call); p.mHandler.drain();
            check("excluded focus does not notify: " + owner, p.mContext.broadcasts.isEmpty() && !call.stubReturnedEarly());
        }
        PhoneWindowManager p = policy("com.android.calculator2");
        XC_MethodHook.MethodHookParam call = param(p.new SwipeCallbacks()); sender.beforeHookedMethod(call);
        check("the callback queues one delivery and leaves Android's original call untouched", p.mHandler.queued.size() == 1 && p.mContext.broadcasts.isEmpty() && !call.stubReturnedEarly());
        p.mHandler.drain();
        check("one callback sends exactly one foreground broadcast", p.mContext.broadcasts.size() == 1);
        Intent sent = p.mContext.broadcasts.get(0);
        check("the broadcast addresses only the private ThinkOS receiver", BarsHook.KIOSK_PACKAGE.equals(sent.component.packageName)
            && "moe.kiwi.thinkos.shell.EdgeReturnReceiver".equals(sent.component.className) && sent.flags == (Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
        check("an explicit user Home can wake the stopped OS package",(sent.flags & Intent.FLAG_INCLUDE_STOPPED_PACKAGES)!=0);
        check("delivery uses system_server's own user", p.mContext.user == Process.myUserHandle());

        p.mContext.fail = true; sender.beforeHookedMethod(param(p.new SwipeCallbacks()));
        p.mHandler.drain(); check("an absent or old receiver cannot throw into the handler", p.mContext.broadcasts.size() == 1);
        p.mHandler.accepting = false; sender.beforeHookedMethod(param(p.new SwipeCallbacks()));
        check("a stopping handler sends nothing", p.mHandler.queued.isEmpty());
        p.mFocusedWindow = null; sender.beforeHookedMethod(param(p.new SwipeCallbacks()));
        check("no focused window sends nothing", p.mHandler.queued.isEmpty());
        p.mFocusedWindow = new BarsHookTest.BrokenWindow(); sender.beforeHookedMethod(param(p.new SwipeCallbacks()));
        check("an unreadable owner sends nothing", p.mHandler.queued.isEmpty());
        p.mFocusedWindow = new BarsHookTest.Window("com.android.calculator2");
        p.mHandler = null; sender.beforeHookedMethod(param(p.new SwipeCallbacks()));
        check("a missing handler fails closed", p.mContext.broadcasts.size() == 1);
        p.mHandler = new android.os.Handler(); p.mContext = null;
        sender.beforeHookedMethod(param(p.new SwipeCallbacks()));
        check("a missing context fails closed", p.mHandler.queued.isEmpty());
        sender.beforeHookedMethod(param(new Object()));
        check("a callback missing this$0 is left alone", true);

        BottomSwipeHook.CallbackInstaller installer = new BottomSwipeHook.CallbackInstaller();
        XposedBridge.stubHooks.clear();
        installer.afterHookedMethod(param(new SystemGesturesPointerEventListener(p.new SwipeCallbacks())));
        installer.afterHookedMethod(param(new SystemGesturesPointerEventListener(p.new SwipeCallbacks())));
        check("callback classes are hooked once across listener instances", XposedBridge.stubHooks.size() == 2 && "onSwipeFromBottom".equals(XposedBridge.stubHooks.get(0)[1]) && "onSwipeFromLeft".equals(XposedBridge.stubHooks.get(1)[1]));
        installer.afterHookedMethod(param(new Object()));
        installer.afterHookedMethod(param(new SystemGesturesPointerEventListener(null)));
        installer.afterHookedMethod(param(new SystemGesturesPointerEventListener(new Object())));
        check("missing callbacks or methods do not install a partial hook", XposedBridge.stubHooks.size() == 2);
        check("an app with a similar name still receives the external gesture", BottomSwipeHook.shouldNotify("moe.kiwi.thinkos.test"));
        System.out.println(failed == 0 ? "\nall bottom gesture checks passed" : "\n" + failed + " checks failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
