package android.content;

public final class Intent {
    public static final int FLAG_RECEIVER_FOREGROUND = 0x10000000;
    public static final int FLAG_INCLUDE_STOPPED_PACKAGES = 0x20;
    public ComponentName component;
    public int flags;
    public Intent setComponent(ComponentName value) { component = value; return this; }
    public Intent addFlags(int value) { flags |= value; return this; }
}
