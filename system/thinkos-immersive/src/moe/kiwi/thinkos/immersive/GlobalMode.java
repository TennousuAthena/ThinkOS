package moe.kiwi.thinkos.immersive;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** Optional v2 shell + v2 APK must agree before Android loses its own bars. */
final class GlobalMode {
    static final String META = "moe.kiwi.thinkos.global_gestures";
    static boolean barsReady;
    static boolean callbacksReady;
    private GlobalMode() { }
    @SuppressWarnings("deprecation") // API27 uses this AppOps overload.
    static boolean active(Context context) {
        if (context == null || !barsReady || !callbacksReady || BackSwipeHook.broken) return false;
        try {
            String protocol = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class)
                .invoke(null, "sys.thinkos.shell", "");
            if (!"2".equals(protocol)) return false;
            if (!fullPolicy(Settings.Global.getString(context.getContentResolver(), "policy_control"))) return false;
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(BarsHook.KIOSK_PACKAGE, PackageManager.GET_META_DATA);
            if (app.metaData == null || app.metaData.getInt(META, 0) != 2) return false;
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.uid, BarsHook.KIOSK_PACKAGE) == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable ignored) { return false; }
    }
    private static boolean fullPolicy(String value) {
        if (value == null) return false;
        for (String section : value.split(":")) {
            if (!section.startsWith("immersive.full=")) continue;
            boolean wildcard=false, excluded=false;
            for (String token : section.substring("immersive.full=".length()).split(",")) {
                if ("*".equals(token)) wildcard=true;
                if (token.startsWith("-")) excluded=true;
            }
            if (wildcard && !excluded) return true;
        }
        return false;
    }
}
