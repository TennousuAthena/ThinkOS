package android.provider;
public final class Settings {
    private Settings() { }
    public static final class Global {
        public static String policy="immersive.full=*";
        private Global() { }
        public static String getString(android.content.ContentResolver resolver, String key) {
            if (!"policy_control".equals(key)) throw new IllegalArgumentException("unexpected setting");
            return policy;
        }
    }
}
