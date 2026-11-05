package android.view;
public final class KeyEvent extends InputEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, KEYCODE_BACK = 4;
    public static final int FLAG_FROM_SYSTEM = 8, FLAG_VIRTUAL_HARD_KEY = 64;
    public final long downTime, eventTime;
    public final int action, keyCode, device, flags, source;
    public KeyEvent(long downTime, long eventTime, int action, int keyCode, int repeat, int meta, int device, int scan, int flags, int source) {
        this.downTime=downTime;this.eventTime=eventTime;this.action=action;this.keyCode=keyCode;this.device=device;this.flags=flags;this.source=source;
        if (repeat != 0 || meta != 0 || scan != 0) throw new IllegalArgumentException("unexpected key attributes");
    }
}
