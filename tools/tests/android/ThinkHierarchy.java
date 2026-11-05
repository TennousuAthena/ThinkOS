import com.android.uiautomator.core.Configurator;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.xmlpull.v1.XmlSerializer;

/** Snapshot an intentionally live UI without waiting for its periodic data updates to stop. */
public class ThinkHierarchy extends UiAutomatorTestCase {
    public void testDump() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0);
        getUiDevice().setCompressedLayoutHeirarchy(Boolean.parseBoolean(getParams().getString("compressed", "false")));
        // API-27's stock dumper clips against Display.getSize(), which excludes
        // the nav strip even when the app draws in that immersive area. Read
        // the same accessibility nodes and retain their real screen bounds.
        // These two framework members were verified in this ROM's DEX; this
        // shell-only helper never becomes an application dependency.
        Method access = getUiDevice().getClass().getDeclaredMethod("getAutomatorBridge");
        access.setAccessible(true);
        Object bridge = access.invoke(getUiDevice());
        Field automationField = null;
        for (Class<?> type = bridge.getClass(); type != null; type = type.getSuperclass()) {
            try { automationField = type.getDeclaredField("mUiAutomation"); break; }
            catch (NoSuchFieldException ignored) {}
        }
        if (automationField == null) throw new IllegalStateException("API-27 UiAutomation unavailable");
        automationField.setAccessible(true);
        UiAutomation automation = (UiAutomation) automationField.get(bridge);
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) throw new IllegalStateException("No active window");
        String name = getParams().getString("path");
        if (name == null || !name.matches("[A-Za-z0-9.-]+")) throw new IllegalArgumentException("Invalid snapshot name");
        File output = new File("/data/local/tmp/local/tmp", name);
        File temporary = new File(output.getPath() + ".writing");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                XmlSerializer xml = Xml.newSerializer();
                xml.setOutput(stream, "UTF-8");
                xml.startDocument("UTF-8", true);
                xml.startTag(null, "hierarchy");
                xml.attribute(null, "rotation", Integer.toString(getUiDevice().getDisplayRotation()));
                if (Boolean.parseBoolean(getParams().getString("allWindows", "false"))) {
                    AccessibilityServiceInfo info = automation.getServiceInfo();
                    info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                    automation.setServiceInfo(info);
                    List<AccessibilityWindowInfo> windows = automation.getWindows();
                    if (windows.size() > 32) throw new IllegalStateException("Too many windows");
                    int[] count = new int[]{0};
                    for (int i = 0; i < windows.size(); i++) {
                        AccessibilityNodeInfo window = windows.get(i).getRoot();
                        if (window == null) continue;
                        try {
                            Rect clip = new Rect(); window.getBoundsInScreen(clip);
                            write(xml, window, i, 0, count, clip);
                        } finally { window.recycle(); }
                    }
                } else {
                    Rect windowBounds = new Rect(); root.getBoundsInScreen(windowBounds);
                    write(xml, root, 0, 0, new int[]{0}, windowBounds);
                }
                xml.endTag(null, "hierarchy");
                xml.endDocument();
            }
            if (!temporary.renameTo(output)) throw new IllegalStateException("Snapshot publication failed");
        } finally { root.recycle(); temporary.delete(); }
    }

    private static String clean(CharSequence value) {
        if (value == null) return "";
        String text = value.toString();
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i); i += Character.charCount(cp);
            if (cp == 9 || cp == 10 || cp == 13 || cp >= 32 && cp <= 0xd7ff || cp >= 0xe000 && cp <= 0xfffd || cp >= 0x10000 && cp <= 0x10ffff) out.appendCodePoint(cp);
        }
        return out.toString();
    }
    private static void attribute(XmlSerializer xml, String name, Object value) throws Exception {
        xml.attribute(null, name, String.valueOf(value));
    }
    private static void write(XmlSerializer xml, AccessibilityNodeInfo node, int index, int depth, int[] count, Rect clip) throws Exception {
        if (depth > 80 || ++count[0] > 5000) throw new IllegalStateException("Hierarchy limit exceeded");
        xml.startTag(null, "node");
        attribute(xml, "index", index);
        attribute(xml, "text", clean(node.getText()));
        attribute(xml, "resource-id", clean(node.getViewIdResourceName()));
        attribute(xml, "class", clean(node.getClassName()));
        attribute(xml, "package", clean(node.getPackageName()));
        attribute(xml, "content-desc", clean(node.getContentDescription()));
        attribute(xml, "checkable", node.isCheckable()); attribute(xml, "checked", node.isChecked());
        attribute(xml, "clickable", node.isClickable()); attribute(xml, "enabled", node.isEnabled());
        attribute(xml, "focusable", node.isFocusable()); attribute(xml, "focused", node.isFocused());
        attribute(xml, "scrollable", node.isScrollable()); attribute(xml, "long-clickable", node.isLongClickable());
        attribute(xml, "password", node.isPassword()); attribute(xml, "selected", node.isSelected());
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (!bounds.intersect(clip)) bounds.setEmpty();
        attribute(xml, "bounds", bounds.toShortString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (child.isVisibleToUser()) write(xml, child, i, depth + 1, count, bounds); }
            finally { child.recycle(); }
        }
        xml.endTag(null, "node");
    }
}
