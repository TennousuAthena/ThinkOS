package android.os;
public final class SystemProperties {
    public static String shell = "";
    private SystemProperties() { }
    public static String get(String key, String fallback) {
        if (!"sys.thinkos.shell".equals(key)) throw new IllegalArgumentException("unexpected property");
        return shell;
    }
}
