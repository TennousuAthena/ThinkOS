import android.app.UiAutomation;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Shell-only API-27 input fixture. Holds a real pointer so the host can observe before UP/CANCEL. */
public class ThinkTouch extends UiAutomatorTestCase {
    private UiAutomation automation;
    private void inject(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { if (!automation.injectInputEvent(event, true)) throw new IllegalStateException("Input rejected"); }
        finally { event.recycle(); }
    }

    private void injectPair(long down, int action, float x, float y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords(); coords[i].x = x + i * 30; coords[i].y = y;
            coords[i].pressure = 1; coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 2, properties, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { if (!automation.injectInputEvent(event, true)) throw new IllegalStateException("Pair input rejected"); }
        finally { event.recycle(); }
    }

    public void testDrag() throws Exception {
        float x1 = Float.parseFloat(getParams().getString("x1"));
        float y1 = Float.parseFloat(getParams().getString("y1"));
        float x2 = Float.parseFloat(getParams().getString("x2"));
        float y2 = Float.parseFloat(getParams().getString("y2"));
        int hold = Integer.parseInt(getParams().getString("hold", "4000"));
        if (hold < 0 || hold > 10000) throw new IllegalArgumentException("Hold outside test limit");
        prepareAutomation();
        long down = SystemClock.uptimeMillis();
        boolean finished = false;
        inject(down, MotionEvent.ACTION_DOWN, x1, y1);
        try {
            for (int i = 1; i <= 8; i++) {
                SystemClock.sleep(25);
                inject(down, MotionEvent.ACTION_MOVE, x1+(x2-x1)*i/8f, y1+(y2-y1)*i/8f);
            }
            System.out.println("THINKOS_TOUCH_HOLD"); System.out.flush();
            SystemClock.sleep(hold);
            inject(down, "cancel".equals(getParams().getString("finish")) ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, x2, y2);
            finished = true;
        } finally { if (!finished) inject(down, MotionEvent.ACTION_CANCEL, x2, y2); }
    }

    private void prepareAutomation() throws Exception {
        Method access = getUiDevice().getClass().getDeclaredMethod("getAutomatorBridge");
        access.setAccessible(true);
        Object bridge = access.invoke(getUiDevice());
        Field field = null;
        for (Class<?> type = bridge.getClass(); type != null; type = type.getSuperclass()) {
            try { field = type.getDeclaredField("mUiAutomation"); break; }
            catch (NoSuchFieldException ignored) {}
        }
        if (field == null) throw new IllegalStateException("API-27 UiAutomation unavailable");
        field.setAccessible(true);
        automation = (UiAutomation) field.get(bridge);
    }

    public void testChord() throws Exception {
        float x1 = Float.parseFloat(getParams().getString("x1"));
        float y1 = Float.parseFloat(getParams().getString("y1"));
        float x2 = Float.parseFloat(getParams().getString("x2"));
        float y2 = Float.parseFloat(getParams().getString("y2"));
        int hold = Integer.parseInt(getParams().getString("hold", "4000"));
        if (hold < 0 || hold > 10000) throw new IllegalArgumentException("Hold outside test limit");
        prepareAutomation();
        long down = SystemClock.uptimeMillis();
        boolean finished = false, pair = false;
        inject(down, MotionEvent.ACTION_DOWN, x1, y1);
        try {
            for (int i = 1; i <= 8; i++) {
                SystemClock.sleep(25);
                inject(down, MotionEvent.ACTION_MOVE, x1+(x2-x1)*i/8f, y1+(y2-y1)*i/8f);
            }
            injectPair(down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x2, y2);
            pair = true;
            System.out.println("THINKOS_TOUCH_HOLD"); System.out.flush();
            SystemClock.sleep(hold);
            injectPair(down, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x2, y2);
            pair = false;
            inject(down, MotionEvent.ACTION_UP, x2, y2);
            finished = true;
        } finally {
            if (!finished) {
                if (pair) injectPair(down, MotionEvent.ACTION_CANCEL, x2, y2);
                else inject(down, MotionEvent.ACTION_CANCEL, x2, y2);
            }
        }
    }
}
