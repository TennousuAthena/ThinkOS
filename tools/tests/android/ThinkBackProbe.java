import android.os.Process;
import java.lang.reflect.InvocationTargetException;
import org.json.JSONObject;

/** Temporary API27 probe of the exact module emitter. No receiver, socket, arbitrary key or root call. */
public final class ThinkBackProbe {
    private ThinkBackProbe() { }
    public static void main(String[] args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT != 27 || args.length != 0) throw new IllegalStateException("API27 fixed Back probe only");
        JSONObject result=new JSONObject().put("uid",Process.myUid());
        try {
            java.lang.reflect.Method method=Class.forName("moe.kiwi.thinkos.immersive.BackSwipeHook").getDeclaredMethod("injectBack");
            method.setAccessible(true);method.invoke(null);result.put("accepted",true).put("deniedSynchronously",false);
        } catch (Throwable error) {
            Throwable cause=error;
            while(cause instanceof InvocationTargetException && cause.getCause()!=null)cause=cause.getCause();
            result.put("accepted",false).put("deniedSynchronously",cause instanceof SecurityException).put("error",cause.getClass().getSimpleName());
        }
        System.out.println("THINKOS_BACK_PROBE "+result.toString());
    }
}
