import android.content.res.Configuration;
import android.os.LocaleList;
import android.os.Process;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Temporary shell-UID API27 fixture. Uses existing shell permission; never grants any application permission. */
public final class ThinkLocale {
    private ThinkLocale() { }
    private static Object activityManager() throws Exception {
        Method method = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService");
        method.setAccessible(true); return method.invoke(null);
    }
    private static Configuration configuration() throws Exception {
        return (Configuration) Class.forName("android.app.IActivityManager").getMethod("getConfiguration").invoke(activityManager());
    }
    private static boolean permission() throws Exception {
        Object packages = Class.forName("android.app.AppGlobals").getDeclaredMethod("getPackageManager").invoke(null);
        Object result = Class.forName("android.content.pm.IPackageManager").getMethod("checkUidPermission", String.class, int.class)
            .invoke(packages, "android.permission.CHANGE_CONFIGURATION", Process.myUid());
        return ((Integer) result).intValue() == 0;
    }
    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 || android.os.Build.VERSION.SDK_INT != 27) throw new IllegalStateException("Shell UID on API27 required");
        Class<?> picker = Class.forName("com.android.internal.app.LocalePicker");
        Method update = picker.getDeclaredMethod("updateLocales", LocaleList.class);
        String action = args.length == 0 ? "read" : args[0];
        if (!"read".equals(action)) {
            if (!permission()) throw new SecurityException("Shell does not already hold CHANGE_CONFIGURATION");
            if (args.length < 2 || !args[1].matches("[A-Za-z0-9,-]{1,512}")) throw new IllegalArgumentException("Invalid locale list");
            LocaleList locales = LocaleList.forLanguageTags(args[1]);
            if (locales.isEmpty()) throw new IllegalArgumentException("Empty locale list");
            if ("set".equals(action)) update.invoke(null, locales);
            else if ("restore".equals(action) && args.length == 3 && ("true".equals(args[2]) || "false".equals(args[2]))) {
                // LocalePicker persists the locale property as well as notifying running clients.
                // Restoring only Configuration with userSetLocale=false leaves the last test
                // language in persist.sys.locale even when getConfiguration reports the original.
                update.invoke(null, locales);
                Configuration value = configuration(); value.setLocales(locales);
                Configuration.class.getField("userSetLocale").setBoolean(value, Boolean.parseBoolean(args[2]));
                Class.forName("android.app.IActivityManager").getMethod("updatePersistentConfiguration", Configuration.class).invoke(activityManager(), value);
            } else throw new IllegalArgumentException("Unknown action");
        }
        Configuration value = configuration();
        System.out.println("THINKOS_LOCALE " + new JSONObject().put("tags", value.getLocales().toLanguageTags())
            .put("userSetLocale", Configuration.class.getField("userSetLocale").getBoolean(value))
            .put("layoutDirection", value.getLayoutDirection()).put("uid", Process.myUid()).put("canChange", permission())
            .put("persistedLocale", Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "persist.sys.locale")));
    }
}
