package de.robv.android.xposed;

public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }

        /** Test-only: the real API keeps this to itself. */
        public boolean stubReturnedEarly() {
            return returnEarly;
        }
    }

    public class Unhook {
    }
}
