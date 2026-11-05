package android.content;

import android.os.UserHandle;
import java.util.ArrayList;
import java.util.List;

public class Context {
    public final List<Intent> broadcasts = new ArrayList<>();
    public UserHandle user;
    public boolean fail;
    public final android.content.pm.PackageManager packages = new android.content.pm.PackageManager();
    public final android.app.AppOpsManager appops = new android.app.AppOpsManager();
    public android.content.pm.PackageManager getPackageManager() { return packages; }
    public ContentResolver getContentResolver() { return new ContentResolver(); }
    public <T> T getSystemService(Class<T> type) { return type.cast(appops); }
    public void sendBroadcastAsUser(Intent intent, UserHandle target) {
        if (fail) throw new IllegalStateException("old or missing APK");
        broadcasts.add(intent); user = target;
    }
}
