package android.app;
public final class AppOpsManager {
    public static final String OPSTR_SYSTEM_ALERT_WINDOW = "android:system_alert_window";
    public static final int MODE_ALLOWED = 0;
    public int mode;
    public int checkOpNoThrow(String operation, int uid, String name) {
        if (!OPSTR_SYSTEM_ALERT_WINDOW.equals(operation) || uid != 10074 || !"moe.kiwi.thinkos".equals(name)) throw new IllegalArgumentException("unexpected appop");
        return mode;
    }
}
