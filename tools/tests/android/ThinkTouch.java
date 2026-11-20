import android.app.UiAutomation;
import android.graphics.Point;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Shell-only API-27 input fixture. Holds a real pointer so the host can observe before UP/CANCEL. */
public class ThinkTouch extends UiAutomatorTestCase {
    private UiAutomation automation;
    private void inject(long down, int action, float x, float y) {
        inject(down, action, x, y, true);
    }

    private void inject(long down, int action, float x, float y, boolean synchronous) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { if (!automation.injectInputEvent(event, synchronous)) throw new IllegalStateException("Input rejected"); }
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

    public void testPath() throws Exception {
        String path = getParams().getString("path", "");
        String finish = getParams().getString("finish", "up");
        if (!path.matches("/data/local/tmp/thinkos-path-[0-9a-f]{16}[.]txt") ||
                !(finish.equals("up") || finish.equals("cancel"))) {
            throw new IllegalArgumentException("Invalid path arguments");
        }
        File file = new File(path);
        if (!file.isFile() || !file.getCanonicalPath().equals(path) || file.length() > 128 * 1024) {
            throw new IllegalArgumentException("Invalid path file");
        }
        Object bridge = automationBridge();
        Method displayMethod = Class.forName("com.android.uiautomator.core.UiAutomatorBridge").getDeclaredMethod("getDefaultDisplay");
        displayMethod.setAccessible(true);
        Display display = (Display) displayMethod.invoke(bridge);
        Point size = new Point();
        display.getRealSize(size);
        int[] times = new int[2000];
        float[] xs = new float[times.length];
        float[] ys = new float[times.length];
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (count == times.length || line.length() > 96) throw new IllegalArgumentException("Path exceeds bounds");
                String[] fields = line.trim().split(" +");
                if (fields.length != 3) throw new IllegalArgumentException("Expected t_ms x y");
                int time = Integer.parseInt(fields[0]);
                float x = Float.parseFloat(fields[1]);
                float y = Float.parseFloat(fields[2]);
                if (time > 10000 || (count == 0 ? time != 0 : time <= times[count - 1]) ||
                        !(0 <= x && x < size.x && 0 <= y && y < size.y)) {
                    throw new IllegalArgumentException("Touch sample outside bounds");
                }
                times[count] = time;
                xs[count] = x;
                ys[count++] = y;
            }
        }
        if (count < 2) throw new IllegalArgumentException("Expected DOWN and UP/CANCEL");
        prepareAutomation(bridge);
        String preparePage = getParams().getString("prepare_page", "");
        long settledAt = 0;
        if (!preparePage.isEmpty()) {
            int delay = Integer.parseInt(getParams().getString("settle_delay", "0"));
            settledAt = waitForPreparedPage(preparePage, delay);
        }
        long down = SystemClock.uptimeMillis();
        if (settledAt > 0) System.out.println("THINKOS_DOWN_AFTER_SETTLE " + (down - settledAt));
        inject(down, MotionEvent.ACTION_DOWN, xs[0], ys[0]);
        int index = 1;
        boolean finished = false;
        try {
            for (; index < count; index++) {
                long delay = down + times[index] - SystemClock.uptimeMillis();
                if (delay > 0) SystemClock.sleep(delay);
                boolean last = index == count - 1;
                int action = last ? (finish.equals("cancel") ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP)
                        : MotionEvent.ACTION_MOVE;
                inject(down, action, xs[index], ys[index], last);
            }
            finished = true;
        } finally {
            if (!finished) inject(down, MotionEvent.ACTION_CANCEL, xs[index - 1], ys[index - 1]);
        }
    }

    private long waitForPreparedPage(String page, int delay) throws Exception {
        if (!(page.equals("aero.main") || page.equals("tiles.apps")) || delay < 0 || delay > 500) {
            throw new IllegalArgumentException("Bad warmup timing fixture");
        }
        broadcast("page", "--es", "value", page);
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            String output = broadcast("stage_state");
            int start = output.indexOf("data=\"{");
            int end = output.lastIndexOf("}\"");
            if (start < 0 || end < start) continue;
            JSONObject state = new JSONObject(output.substring(start + 6, end + 1));
            long settled = state.optLong("warmSettledAt");
            if (page.equals(state.optString("page")) && state.optBoolean("settled") && settled > 0) {
                SystemClock.sleep(Math.max(0, settled + delay - SystemClock.uptimeMillis()));
                return settled;
            }
        }
        throw new IllegalStateException("Warmup fixture never settled: " + page);
    }

    private String broadcast(String command, String... extras) throws Exception {
        String[] base = {"/system/bin/am", "broadcast", "-n", "moe.kiwi.thinkos/.DebugReceiver", "--es", "command", command};
        String[] args = java.util.Arrays.copyOf(base, base.length + extras.length);
        System.arraycopy(extras, 0, args, base.length, extras.length);
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            for (String line; (line = reader.readLine()) != null;) output.append(line).append('\n');
        }
        if (process.waitFor() != 0) throw new IllegalStateException("Warmup fixture broadcast failed");
        return output.toString();
    }

    private Object automationBridge() throws Exception {
        Method access = getUiDevice().getClass().getDeclaredMethod("getAutomatorBridge");
        access.setAccessible(true);
        return access.invoke(getUiDevice());
    }

    private void prepareAutomation() throws Exception {
        prepareAutomation(automationBridge());
    }

    private void prepareAutomation(Object bridge) throws Exception {
        Field field = null;
        for (Class<?> type = bridge.getClass(); type != null; type = type.getSuperclass()) {
            try { field = type.getDeclaredField("mUiAutomation"); break; }
            catch (NoSuchFieldException ignored) {}
        }
        if (field == null) throw new IllegalStateException("API-27 UiAutomation unavailable");
        field.setAccessible(true);
        automation = (UiAutomation) field.get(bridge);
    }

    public void testClickNode() throws Exception {
        String label = getParams().getString("label");
        if (label == null || label.isEmpty()) throw new IllegalArgumentException("Missing node label");
        prepareAutomation();
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        assertNotNull("Missing active window", root);
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
            if (node.getText() != null && label.contentEquals(node.getText()) && node.isVisibleToUser() && node.isClickable()) {
                assertTrue("ACTION_CLICK rejected", node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                return;
            }
        }
        fail("Visible clickable node not found: " + label);
    }

    public void testSetEditorText() throws Exception {
        String text = getParams().getString("text");
        if (text == null || text.length() > 1024) throw new IllegalArgumentException("Invalid editor text");
        prepareAutomation();
        AccessibilityNodeInfo editor = automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        assertNotNull("No focused editor", editor);
        assertTrue("Expected the ThinkOS native editor", editor.isEditable() && editor.isVisibleToUser() &&
                "moe.kiwi.thinkos".contentEquals(editor.getPackageName()));
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        assertTrue("ACTION_SET_TEXT rejected", editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
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
