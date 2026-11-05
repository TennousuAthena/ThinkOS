import android.os.Build;
import android.os.Process;
import android.system.Os;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Loads the installed APK's production identity code. Only fresh private cache
 * directories are writable; real settings, discovery and pairing seeds are untouched. */
public final class ThinkCastIdentity {
    private static final JSONArray checks = new JSONArray();
    private ThinkCastIdentity() { }
    private static void check(String name, boolean ok) throws Exception {
        checks.put(new JSONObject().put("name",name).put("passed",ok));
        if (!ok) throw new IllegalStateException(name);
    }
    private static void write(File file, byte[] bytes) throws Exception {
        File parent=file.getParentFile();
        if (parent==null || (!parent.isDirectory() && !parent.mkdirs())) throw new IllegalStateException("cache directory");
        try (FileOutputStream out=new FileOutputStream(file)) { out.write(bytes);out.getFD().sync(); }
        Os.chmod(file.getPath(),0600);
    }
    private static String digest(String value) throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    public static void main(String[] args) throws Exception {
        if (Build.VERSION.SDK_INT!=27 || Process.myUid()<10000 || args.length!=3) throw new IllegalArgumentException("API27 app-UID probe only");
        File root=new File(args[2]);
        if (!root.getCanonicalPath().matches("/data/(user/0|data)/moe\\.kiwi\\.thinkos/cache/cast-identity-probe-[0-9a-f]{32}"))
            throw new IllegalArgumentException("private probe directory only");
        PathClassLoader loader=new PathClassLoader(args[1],ThinkCastIdentity.class.getClassLoader());
        Class<?> type=loader.loadClass("moe.kiwi.thinkos.cast.CastIdentity");
        Object companion=type.getField("Companion").get(null);
        Method load=companion.getClass().getMethod("load",File.class);
        Method uuid=type.getMethod("getUuid"),seed=type.getMethod("getAirplaySeed");
        Class<?> records=loader.loadClass("moe.kiwi.thinkos.cast.airplay.AirPlayRecord");
        Object record=records.getField("INSTANCE").get(null);
        Method deviceId=records.getMethod("deviceId",String.class);
        File first=new File(root,"first/identity-seed"),expected=new File(root,"expected.txt");
        Object a=load.invoke(companion,first);
        String aUuid=(String)uuid.invoke(a),aMac=(String)deviceId.invoke(record,seed.invoke(a));
        if ("create".equals(args[0])) {
            check("installed production class creates a 32-byte seed",first.length()==32);
            check("Android file mode is owner read/write only",(Os.stat(first.getPath()).st_mode&0777)==0600);
            byte[] original=Files.readAllBytes(first.toPath());
            Object again=load.invoke(companion,first);
            check("independent loads preserve UUID and AirPlay deviceid",aUuid.equals(uuid.invoke(again)) && aMac.equals(deviceId.invoke(record,seed.invoke(again))));
            Object b=load.invoke(companion,new File(root,"second/identity-seed"));
            check("fresh private directories have different discovery identities",!aUuid.equals(uuid.invoke(b)) && !aMac.equals(deviceId.invoke(record,seed.invoke(b))));
            check("AirPlay discovery ID is a locally administered unicast address",aMac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}") && (Integer.parseInt(aMac.substring(0,2),16)&3)==2);
            Os.chmod(first.getPath(),0644);load.invoke(companion,first);
            check("existing broad permissions are repaired without changing the seed",(Os.stat(first.getPath()).st_mode&0777)==0600 && Arrays.equals(original,Files.readAllBytes(first.toPath())));
            File damaged=new File(root,"damaged/identity-seed");
            Object before=load.invoke(companion,damaged);write(damaged,new byte[]{1,2,3});
            Object repaired=load.invoke(companion,damaged);
            check("truncated seed regenerates and then remains stable",damaged.length()==32 && !uuid.invoke(before).equals(uuid.invoke(repaired)) && uuid.invoke(repaired).equals(uuid.invoke(load.invoke(companion,damaged))));
            write(damaged,new byte[1_048_576]);Object after=load.invoke(companion,damaged);
            check("oversized seed regenerates to the fixed format",damaged.length()==32 && !uuid.invoke(repaired).equals(uuid.invoke(after)));
            File pairing=new File(first.getParentFile(),"airplay-identity");byte[] key=new byte[32];Arrays.fill(key,(byte)19);write(pairing,key);
            load.invoke(companion,first);
            check("discovery identity load preserves the separate pairing key",Arrays.equals(key,Files.readAllBytes(pairing.toPath())));
            write(expected,digest(aUuid+"/"+aMac).getBytes(StandardCharsets.US_ASCII));
        } else if ("reload".equals(args[0])) {
            String saved=new String(Files.readAllBytes(expected.toPath()),StandardCharsets.US_ASCII);
            check("a fresh ART process keeps the same UUID and AirPlay deviceid",saved.equals(digest(aUuid+"/"+aMac)));
            check("a fresh ART process retains the owner-only seed",first.length()==32 && (Os.stat(first.getPath()).st_mode&0777)==0600);
        } else throw new IllegalArgumentException("create or reload");
        System.out.println("THINKOS_CAST_IDENTITY "+new JSONObject().put("pid",Process.myPid()).put("checks",checks));
    }
}
