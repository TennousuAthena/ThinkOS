package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException error) {
            throw new NoClassDefFoundError(className);
        }
    }

    public static Object getObjectField(Object object, String fieldName) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException next) {
                // Look in the superclass.
            } catch (IllegalAccessException error) {
                throw new IllegalAccessError(error.getMessage());
            }
        }
        throw new NoSuchFieldError(fieldName);
    }

    /** Only by name and argument count: enough for the calls the module makes. */
    public static Object callMethod(Object object, String methodName, Object... args) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                    try {
                        method.setAccessible(true);
                        return method.invoke(object, args);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        throw new RuntimeException(error.getCause());
                    } catch (IllegalAccessException error) {
                        throw new IllegalAccessError(error.getMessage());
                    }
                }
            }
        }
        throw new NoSuchMethodError(methodName);
    }
}
