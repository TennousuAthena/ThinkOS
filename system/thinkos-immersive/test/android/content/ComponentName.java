package android.content;

public final class ComponentName {
    public final String packageName;
    public final String className;
    public ComponentName(String packageName, String className) { this.packageName = packageName; this.className = className; }
}
