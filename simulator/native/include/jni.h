/* MIT. Deliberately limited JNI surface used by ThinkOS' unmodified bridge.
 * This is an adapter contract, not a JVM ABI implementation. */
#ifndef THINKOS_SIMULATOR_JNI_H
#define THINKOS_SIMULATOR_JNI_H
#include <stdint.h>
#include <stddef.h>
typedef uint8_t jboolean;
typedef uint16_t jchar;
typedef int32_t jint;
typedef int32_t jsize;
typedef int64_t jlong;
typedef double jdouble;
typedef struct TosObject *jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jobjectArray;
typedef struct TosMethod *jmethodID;
typedef const struct JNINativeInterface_ *JNIEnv;
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
struct JNINativeInterface_ {
    jclass (*FindClass)(JNIEnv *, const char *);
    jobject (*NewGlobalRef)(JNIEnv *, jobject);
    void (*DeleteGlobalRef)(JNIEnv *, jobject);
    void (*DeleteLocalRef)(JNIEnv *, jobject);
    jint (*PushLocalFrame)(JNIEnv *, jint);
    jobject (*PopLocalFrame)(JNIEnv *, jobject);
    jmethodID (*GetMethodID)(JNIEnv *, jclass, const char *, const char *);
    jobject (*NewObject)(JNIEnv *, jclass, jmethodID, ...);
    jobject (*CallObjectMethod)(JNIEnv *, jobject, jmethodID, ...);
    jboolean (*CallBooleanMethod)(JNIEnv *, jobject, jmethodID, ...);
    jint (*CallIntMethod)(JNIEnv *, jobject, jmethodID, ...);
    jlong (*CallLongMethod)(JNIEnv *, jobject, jmethodID, ...);
    jdouble (*CallDoubleMethod)(JNIEnv *, jobject, jmethodID, ...);
    jboolean (*IsInstanceOf)(JNIEnv *, jobject, jclass);
    jclass (*GetObjectClass)(JNIEnv *, jobject);
    jboolean (*ExceptionCheck)(JNIEnv *);
    void (*ExceptionClear)(JNIEnv *);
    jstring (*NewString)(JNIEnv *, const jchar *, jsize);
    jstring (*NewStringUTF)(JNIEnv *, const char *);
    const jchar *(*GetStringChars)(JNIEnv *, jstring, jboolean *);
    void (*ReleaseStringChars)(JNIEnv *, jstring, const jchar *);
    jsize (*GetStringLength)(JNIEnv *, jstring);
    void (*GetStringRegion)(JNIEnv *, jstring, jsize, jsize, jchar *);
    jobjectArray (*NewObjectArray)(JNIEnv *, jsize, jclass, jobject);
    jsize (*GetArrayLength)(JNIEnv *, jobjectArray);
    jobject (*GetObjectArrayElement)(JNIEnv *, jobjectArray, jsize);
    void (*SetObjectArrayElement)(JNIEnv *, jobjectArray, jsize, jobject);
};
#endif
