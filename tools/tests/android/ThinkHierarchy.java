import com.android.uiautomator.core.Configurator;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

/** Snapshot an intentionally live UI without waiting for its periodic data updates to stop. */
public class ThinkHierarchy extends UiAutomatorTestCase {
    public void testDump() {
        Configurator.getInstance().setWaitForIdleTimeout(0);
        getUiDevice().dumpWindowHierarchy(getParams().getString("path"));
    }
}
