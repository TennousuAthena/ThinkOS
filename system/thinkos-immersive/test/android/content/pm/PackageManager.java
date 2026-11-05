package android.content.pm;
public final class PackageManager {
    public static final int GET_META_DATA = 128;
    public final ApplicationInfo app = new ApplicationInfo();
    public boolean missing;
    public ApplicationInfo getApplicationInfo(String name, int flags) {
        if (missing || !"moe.kiwi.thinkos".equals(name) || flags != GET_META_DATA) throw new IllegalArgumentException("unknown package");
        return app;
    }
}
