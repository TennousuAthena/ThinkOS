package android.os;
public final class Bundle {
    public int protocol;
    public int getInt(String name, int fallback) {
        if (!"moe.kiwi.thinkos.global_gestures".equals(name)) throw new IllegalArgumentException("unexpected metadata");
        return protocol;
    }
}
