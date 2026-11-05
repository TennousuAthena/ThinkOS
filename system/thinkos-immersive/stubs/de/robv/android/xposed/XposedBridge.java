package de.robv.android.xposed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class XposedBridge {
    /** Test-only: what was logged, and which (class, method, callback) were hooked. */
    public static final List<String> stubLog = new ArrayList<>();
    public static final List<Object[]> stubHooks = new ArrayList<>();

    private XposedBridge() {
    }

    public static synchronized void log(String text) {
        stubLog.add(text);
    }

    /** Counts the declared methods of that name, as the real one hooks each of them. */
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new HashSet<>();
        for (java.lang.reflect.Method method : hookClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                stubHooks.add(new Object[] {hookClass, methodName, callback});
                result.add(callback.new Unhook());
            }
        }
        return result;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new HashSet<>();
        for (java.lang.reflect.Constructor<?> constructor : hookClass.getDeclaredConstructors()) {
            stubHooks.add(new Object[] {hookClass, "<init>", callback});
            result.add(callback.new Unhook());
        }
        return result;
    }
}
