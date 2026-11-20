#ifndef THINKOS_SIMULATOR_SHIM_H
#define THINKOS_SIMULATOR_SHIM_H
#include "jni.h"
enum { TOS_NULL, TOS_BOOL, TOS_INT, TOS_DOUBLE, TOS_STRING, TOS_ARRAY, TOS_MAP,
       TOS_STEP, TOS_CLASS, TOS_HOST, TOS_ITERATOR, TOS_ENTRY };
JNIEnv *tos_environment(void);
int tos_frame_push(void);
void tos_frame_pop(void);
jobject tos_value_bool(int);
jobject tos_value_int(int64_t);
jobject tos_value_double(double);
jobject tos_value_string(const uint16_t *, int);
jobject tos_value_array(int);
jobject tos_value_map(void);
void tos_array_set(jobject, int, jobject);
void tos_map_set(jobject, jobject, jobject);
int tos_value_kind(jobject);
int tos_value_length(jobject);
int64_t tos_value_i64(jobject);
double tos_value_f64(jobject);
const uint16_t *tos_value_chars(jobject);
jobject tos_value_get(jobject, int);
jobject tos_value_key(jobject, int);
jobject tos_new_host(void);
int tos_step_status(jobject);
int tos_step_task(jobject);
jobject tos_step_message(jobject);
jobject tos_step_method(jobject);
int64_t tos_step_metric(jobject, int);
void tos_host_exception(void);
size_t tos_shim_objects(void);
typedef jobject (*TosHostCallback)(const char *, jobject, int);
void tos_set_host_callback(TosHostCallback);
#endif
