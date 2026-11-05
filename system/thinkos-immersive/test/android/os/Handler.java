package android.os;

import java.util.ArrayList;
import java.util.List;

public class Handler {
    public final List<Runnable> queued = new ArrayList<>();
    public boolean accepting = true;
    public boolean post(Runnable runnable) { if (!accepting) return false; queued.add(runnable); return true; }
    public void drain() { for (Runnable task : new ArrayList<>(queued)) task.run(); queued.clear(); }
}
