package android.os;

public final class Process {
    private static final UserHandle USER = new UserHandle();
    private Process() { }
    public static UserHandle myUserHandle() { return USER; }
}
