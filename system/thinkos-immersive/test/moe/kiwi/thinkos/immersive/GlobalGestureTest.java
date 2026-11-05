package moe.kiwi.thinkos.immersive;

import android.hardware.input.InputManager;
import android.os.SystemProperties;
import android.view.KeyEvent;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.policy.SystemGesturesPointerEventListener;
import de.robv.android.xposed.XC_MethodHook;

public final class GlobalGestureTest {
    private static int failed;
    private GlobalGestureTest() { }
    private static void check(String name, boolean ok) { if(!ok)failed++;System.out.println((ok?"PASS ":"FAIL ")+name); }
    private static XC_MethodHook.MethodHookParam param(Object object) { XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=object;return p; }
    private static PhoneWindowManager policy(String owner) {
        PhoneWindowManager p=new PhoneWindowManager();p.mFocusedWindow=new BarsHookTest.Window(owner);
        p.mContext.packages.app.metaData.protocol=2;return p;
    }
    public static void main(String[] args) throws Throwable {
        PhoneWindowManager p=policy("com.android.settings");SystemProperties.shell="2";
        GlobalMode.callbacksReady=true;
        check("a missing bars hook cannot enable global interception",!GlobalMode.active(p.mContext));
        GlobalMode.barsReady=true;GlobalMode.callbacksReady=false;
        check("both native edge callbacks must be hooked",!GlobalMode.active(p.mContext));
        GlobalMode.callbacksReady=true;SystemProperties.shell="";
        check("missing optional shell keeps Android bars",!GlobalMode.active(p.mContext));
        SystemProperties.shell="1";check("legacy shell does not enable global interception",!GlobalMode.active(p.mContext));
        SystemProperties.shell="2";p.mContext.packages.app.metaData.protocol=0;
        check("new module with old APK keeps Android's exits",!GlobalMode.active(p.mContext));
        p.mContext.packages.app.metaData.protocol=2;p.mContext.appops.mode=1;
        check("revoked overlay permission restores Android's exits",!GlobalMode.active(p.mContext));
        p.mContext.appops.mode=0;check("matching shell, APK and overlay permission enable the global mode",GlobalMode.active(p.mContext));
        android.provider.Settings.Global.policy="immersive.status=*";
        check("global interception also requires both bars to be hidden",!GlobalMode.active(p.mContext));
        android.provider.Settings.Global.policy="immersive.full=*";
        BarsHook.GlobalBarFlags flags=new BarsHook.GlobalBarFlags();XC_MethodHook.MethodHookParam calculation=param(p);
        calculation.args=new Object[]{p.mFocusedWindow,0,0x10};flags.beforeHookedMethod(calculation);
        check("an IME-cleared hide flag is restored before system bar calculation",Integer.valueOf(0x1016).equals(calculation.args[2]));
        calculation.setResult(0x0c000010);flags.afterHookedMethod(calculation);
        check("the final global flags hide both bars, remove transient requests and preserve unrelated bits",Integer.valueOf(0x1016).equals(calculation.getResult()));
        p.mContext.appops.mode=1;calculation.args[2]=0x10;calculation.setResult(0x0c000010);flags.beforeHookedMethod(calculation);flags.afterHookedMethod(calculation);
        check("revoked overlay permission leaves the system's ordinary visibility calculation intact",Integer.valueOf(0x10).equals(calculation.args[2]) && Integer.valueOf(0x0c000010).equals(calculation.getResult()));
        p.mContext.appops.mode=0;calculation.setResult(null);flags.afterHookedMethod(calculation);
        check("a failed native calculation is never replaced with a visibility result",calculation.getResult()==null);
        for(String owner:new String[]{"com.android.settings","some.video.app","android","com.android.systemui"}) {
            p.mFocusedWindow=new BarsHookTest.Window(owner);XC_MethodHook.MethodHookParam swipe=param(p);new BarsHook.SwipeGuard().beforeHookedMethod(swipe);
            check("global mode suppresses transient bars for "+owner,swipe.stubReturnedEarly());
        }
        BackSwipeHook back=new BackSwipeHook();p.mFocusedWindow=new BarsHookTest.Window("some.video.app");InputManager.events.clear();
        back.beforeHookedMethod(param(p.new SwipeCallbacks()));check("real left callback queues one fixed Back",p.mHandler.queued.size()==1);p.mHandler.drain();
        check("Back uses one down/up pair",InputManager.events.size()==2);
        KeyEvent down=(KeyEvent)InputManager.events.get(0),up=(KeyEvent)InputManager.events.get(1);
        check("only Back is injectable, with matching timestamps",down.keyCode==4 && up.keyCode==4 && down.action==0 && up.action==1 && down.downTime==up.downTime);
        InputManager.events.clear();back.beforeHookedMethod(param(p.new SwipeCallbacks()));p.mFocusedWindow=new BarsHookTest.Window("a.new.dialog");p.mHandler.drain();
        check("a changed foreground window cancels the queued Back",InputManager.events.isEmpty());
        p.mFocusedWindow=new BarsHookTest.Window(BarsHook.KIOSK_PACKAGE);back.beforeHookedMethod(param(p.new SwipeCallbacks()));p.mHandler.drain();
        check("ThinkOS keeps its own NavStack Back",InputManager.events.isEmpty());
        BarsHookTest.Window own=new BarsHookTest.Window(BarsHook.KIOSK_PACKAGE);own.attrs.type=2;p.mFocusedWindow=own;
        back.beforeHookedMethod(param(p.new SwipeCallbacks()));p.mHandler.drain();
        check("a native ThinkOS dialog receives ordinary Back",InputManager.events.size()==2);InputManager.events.clear();
        own.attrs.type=1;own.mService.mInputMethodWindow=new BarsHookTest.Window("some.keyboard");
        SystemGesturesPointerEventListener listener=new SystemGesturesPointerEventListener(p.new SwipeCallbacks());
        BackSwipeHook imeBack=new BackSwipeHook(listener);imeBack.beforeHookedMethod(param(listener.mCallbacks));p.mHandler.drain();
        check("a left gesture inside the visible IME region dismisses the keyboard",InputManager.events.size()==2);InputManager.events.clear();
        listener.mDownY[0]=100;imeBack.beforeHookedMethod(param(listener.mCallbacks));p.mHandler.drain();
        check("an IME outside the touch region does not steal ThinkOS NavStack Back",InputManager.events.isEmpty());
        listener.mDownY[0]=600;own.mService.mInputMethodWindow.visible=false;imeBack.beforeHookedMethod(param(listener.mCallbacks));p.mHandler.drain();
        check("an invisible IME does not steal ThinkOS NavStack Back",InputManager.events.isEmpty());
        BarsHookTest.Window external=new BarsHookTest.Window("some.video.app");p.mFocusedWindow=external;
        BarsHookTest.Window panel=new BarsHookTest.Window(BarsHook.KIOSK_PACKAGE);panel.attrs.type=2038;panel.attrs.title="ThinkOS Panel";
        external.mService.mWindowMap.put(1,panel);back.beforeHookedMethod(param(p.new SwipeCallbacks()));p.mHandler.drain();
        check("the expanded non-focusable panel owns Back before the external app",InputManager.events.isEmpty());
        panel.attrs.height=39;back.beforeHookedMethod(param(p.new SwipeCallbacks()));p.mHandler.drain();
        check("a narrow closed top edge does not consume external Back",InputManager.events.size()==2);InputManager.events.clear();
        panel.attrs.height=-1;panel.visible=false;back.beforeHookedMethod(param(p.new SwipeCallbacks()));p.mHandler.drain();
        check("a hidden panel does not consume external Back",InputManager.events.size()==2);InputManager.events.clear();
        panel.visible=true;panel=new BarsHookTest.Window("lookalike.app");panel.attrs.type=2038;panel.attrs.title="ThinkOS Panel";
        external.mService.mWindowMap.put(1,panel);back.beforeHookedMethod(param(p.new SwipeCallbacks()));p.mHandler.drain();
        check("another app cannot impersonate the ThinkOS panel by its window title",InputManager.events.size()==2);InputManager.events.clear();
        for(String owner:new String[]{BarsHook.KIOSK_PACKAGE,"android","com.android.systemui"}) {
            p.mFocusedWindow=new BarsHookTest.Window(owner);p.mContext.broadcasts.clear();
            new BottomSwipeHook.Sender().beforeHookedMethod(param(p.new SwipeCallbacks()));p.mHandler.drain();
            check("global Home is also available above native dialogs and system UI: "+owner,p.mContext.broadcasts.size()==1);
        }
        p.mFocusedWindow=new BarsHookTest.Window("some.video.app");InputManager.reject=true;back.beforeHookedMethod(param(p.new SwipeCallbacks()));p.mHandler.drain();
        check("injection failure restores the system bars instead of trapping the app",BackSwipeHook.broken && !GlobalMode.active(p.mContext));
        for(String owner:new String[]{"some.video.app","com.android.settings",BarsHook.KIOSK_PACKAGE}) {
            BarsHookTest.Window window=new BarsHookTest.Window(owner);window.attrs.type=2;p.mFocusedWindow=window;
            XC_MethodHook.MethodHookParam fallback=param(p);new BarsHook.SwipeGuard().beforeHookedMethod(fallback);
            check("the actual transient-bar guard leaves an Android exit after Back failure for "+owner,!fallback.stubReturnedEarly());
        }
        calculation=param(p);calculation.args=new Object[]{p.mFocusedWindow,0,0x10};calculation.setResult(0x0c000010);
        flags.beforeHookedMethod(calculation);flags.afterHookedMethod(calculation);
        check("Back failure also leaves the real visibility calculation and transient requests untouched",
            Integer.valueOf(0x10).equals(calculation.args[2]) && Integer.valueOf(0x0c000010).equals(calculation.getResult()));
        InputManager.reject=false;BackSwipeHook.broken=false;SystemProperties.shell="";
        p.mFocusedWindow=new BarsHookTest.Window(BarsHook.KIOSK_PACKAGE);XC_MethodHook.MethodHookParam legacy=param(p);
        new BarsHook.SwipeGuard().beforeHookedMethod(legacy);
        check("a healthy bridge preserves legacy ThinkOS-only transient-bar suppression",legacy.stubReturnedEarly());
        p.mFocusedWindow=new BarsHookTest.Window("some.video.app");legacy=param(p);new BarsHook.SwipeGuard().beforeHookedMethod(legacy);
        check("an external app without ready global mode keeps its ordinary Android bars",!legacy.stubReturnedEarly());
        System.out.println(failed==0?"\nall global gesture checks passed":"\n"+failed+" checks failed");System.exit(failed==0?0:1);
    }
}
