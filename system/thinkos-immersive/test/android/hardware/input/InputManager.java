package android.hardware.input;
import android.view.InputEvent;
import java.util.ArrayList;
import java.util.List;
public final class InputManager {
    public static final List<InputEvent> events = new ArrayList<>();
    public static boolean reject;
    private static final InputManager INSTANCE = new InputManager();
    private InputManager() { }
    public static InputManager getInstance() { return INSTANCE; }
    public boolean injectInputEvent(InputEvent event, int mode) {
        if (mode != 0) throw new IllegalArgumentException("must not block system_server");
        events.add(event);return !reject;
    }
}
