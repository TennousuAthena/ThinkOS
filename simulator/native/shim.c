/* MIT. Type-preserving object adapter for the production JNI bridge. */
#include "shim.h"
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#ifdef __EMSCRIPTEN__
#include <emscripten.h>
#endif

enum { C_OBJECT, C_MAP, C_LIST, C_STRING, C_NUMBER, C_BOOL, C_LONG, C_DOUBLE,
       C_STEP, C_LINKED_MAP, C_ARRAY_LIST, C_ITERABLE, C_ITERATOR, C_ENTRY, C_HOST, C_COUNT };
enum { M_INIT, M_PUT, M_ENTRIES, M_ITERATOR, M_HAS_NEXT, M_NEXT, M_KEY, M_VALUE,
       M_ADD, M_GET, M_SIZE, M_LONG, M_DOUBLE, M_BOOL, M_CALL };
struct TosMethod { int id; };
typedef struct { jobject key, value; } Pair;
struct TosObject {
    unsigned refs;
    int kind, count, capacity;
    union {
        int64_t integer;
        double real;
        uint16_t *chars;
        jobject *items;
        Pair *pairs;
        struct { jobject owner; int position; } cursor;
        struct { int task, status; jobject message, method; int64_t metrics[7]; } step;
    } value;
};
typedef struct Frame { struct Frame *previous; jobject *refs; size_t count, capacity; } Frame;
static Frame *frame;
static size_t live_objects;
static int exception_pending;
static TosHostCallback host_callback;
static struct TosObject classes[C_COUNT];
static struct TosMethod methods[] = {{M_INIT},{M_PUT},{M_ENTRIES},{M_ITERATOR},{M_HAS_NEXT},
    {M_NEXT},{M_KEY},{M_VALUE},{M_ADD},{M_GET},{M_SIZE},{M_LONG},{M_DOUBLE},{M_BOOL},{M_CALL}};
static const char *class_names[] = {"java/lang/Object", "java/util/Map", "java/util/List",
    "java/lang/String", "java/lang/Number", "java/lang/Boolean", "java/lang/Long",
    "java/lang/Double", "moe/kiwi/thinkos/plugin/NativeLua$Step", "java/util/LinkedHashMap",
    "java/util/ArrayList", "java/lang/Iterable", "java/util/Iterator", "java/util/Map$Entry",
    "moe/kiwi/thinkos/plugin/NativeLua"};

static jobject reject(void) { exception_pending = 1; return NULL; }
void tos_host_exception(void) { exception_pending = 1; }
static void retain(jobject object) { if (object && object->refs != UINT_MAX) object->refs++; }
static void release(jobject object) {
    if (!object || object->refs == UINT_MAX || --object->refs) return;
    switch (object->kind) {
    case TOS_STRING: free(object->value.chars); break;
    case TOS_ARRAY:
        for (int i=0; i<object->count; i++) release(object->value.items[i]);
        free(object->value.items); break;
    case TOS_MAP:
        for (int i=0; i<object->count; i++) { release(object->value.pairs[i].key); release(object->value.pairs[i].value); }
        free(object->value.pairs); break;
    case TOS_ITERATOR: case TOS_ENTRY: release(object->value.cursor.owner); break;
    case TOS_STEP: release(object->value.step.message); release(object->value.step.method); break;
    default: break;
    }
    free(object); live_objects--;
}
static jobject local(jobject object) {
    if (!object || object->refs == UINT_MAX) return object;
    if (!frame) return reject();
    if (frame->count == frame->capacity) {
        size_t capacity = frame->capacity ? frame->capacity * 2 : 32;
        if (capacity > 1048576) return reject();
        jobject *refs = realloc(frame->refs, capacity * sizeof(*refs));
        if (!refs) return reject();
        frame->refs = refs; frame->capacity = capacity;
    }
    retain(object); frame->refs[frame->count++] = object; return object;
}
static jobject object_new(int kind) {
    if (!frame || live_objects >= 1048576) return reject();
    jobject object = calloc(1, sizeof(*object));
    if (!object) return reject();
    object->kind = kind; live_objects++;
    if (!local(object)) { free(object); live_objects--; return NULL; }
    return object;
}
int tos_frame_push(void) {
    Frame *next = calloc(1, sizeof(*next));
    if (!next) { reject(); return -1; }
    next->previous = frame; frame = next; return 0;
}
void tos_frame_pop(void) {
    if (!frame) { reject(); return; }
    Frame *old = frame; frame = old->previous;
    for (size_t i=0; i<old->count; i++) release(old->refs[i]);
    free(old->refs); free(old);
}
static void delete_local(JNIEnv *env, jobject object) {
    (void)env;
    if (!object || object->refs == UINT_MAX) return;
    for (Frame *current=frame; current; current=current->previous) {
        for (size_t i=current->count; i>0; i--) if (current->refs[i-1] == object) {
            current->refs[i-1] = NULL; release(object); return;
        }
    }
    reject();
}
jobject tos_value_bool(int value) { jobject o=object_new(TOS_BOOL); if(o)o->value.integer=value!=0; return o; }
jobject tos_value_int(int64_t value) { jobject o=object_new(TOS_INT); if(o)o->value.integer=value; return o; }
jobject tos_value_double(double value) { jobject o=object_new(TOS_DOUBLE); if(o)o->value.real=value; return o; }
jobject tos_value_string(const uint16_t *chars, int length) {
    if(length<0 || length>1048576 || (length && !chars)) return reject();
    jobject o=object_new(TOS_STRING); if(!o)return NULL;
    o->value.chars=calloc((size_t)length+1,sizeof(uint16_t));
    if(!o->value.chars)return reject();
    if(length)memcpy(o->value.chars,chars,(size_t)length*sizeof(uint16_t));
    o->count=length; return o;
}
jobject tos_value_array(int length) {
    if(length<0 || length>1048576)return reject();
    jobject o=object_new(TOS_ARRAY); if(!o)return NULL;
    o->value.items=calloc((size_t)length+1,sizeof(jobject));
    if(!o->value.items)return reject();
    o->count=o->capacity=length; return o;
}
jobject tos_value_map(void) { return object_new(TOS_MAP); }
void tos_array_set(jobject o,int index,jobject value) {
    if(!o || o->kind!=TOS_ARRAY || index<0 || index>=o->count){reject();return;}
    retain(value); release(o->value.items[index]); o->value.items[index]=value;
}
static int same_string(jobject a,jobject b) {
    return a && b && a->kind==TOS_STRING && b->kind==TOS_STRING && a->count==b->count &&
        !memcmp(a->value.chars,b->value.chars,(size_t)a->count*sizeof(uint16_t));
}
void tos_map_set(jobject o,jobject key,jobject value) {
    if(!o || o->kind!=TOS_MAP || !key || key->kind!=TOS_STRING){reject();return;}
    for(int i=0;i<o->count;i++)if(same_string(o->value.pairs[i].key,key)){
        retain(value); release(o->value.pairs[i].value); o->value.pairs[i].value=value;return;
    }
    if(o->count==o->capacity){
        int capacity=o->capacity?o->capacity*2:16;
        if(capacity>1048576){reject();return;}
        Pair *pairs=realloc(o->value.pairs,(size_t)capacity*sizeof(Pair));
        if(!pairs){reject();return;} o->value.pairs=pairs;o->capacity=capacity;
    }
    retain(key);retain(value);o->value.pairs[o->count++]=(Pair){key,value};
}
int tos_value_kind(jobject o){return o?o->kind:TOS_NULL;}
int tos_value_length(jobject o){return o?o->count:0;}
int64_t tos_value_i64(jobject o){return o?o->value.integer:0;}
double tos_value_f64(jobject o){return o?(o->kind==TOS_DOUBLE?o->value.real:(double)o->value.integer):0;}
const uint16_t *tos_value_chars(jobject o){return o&&o->kind==TOS_STRING?o->value.chars:NULL;}
jobject tos_value_get(jobject o,int index){
    if(!o||index<0||index>=o->count)return reject();
    if(o->kind==TOS_ARRAY)return o->value.items[index];
    if(o->kind==TOS_MAP)return o->value.pairs[index].value;
    return reject();
}
jobject tos_value_key(jobject o,int index){
    if(!o||o->kind!=TOS_MAP||index<0||index>=o->count)return reject();
    return o->value.pairs[index].key;
}
jobject tos_new_host(void){return object_new(TOS_HOST);}
int tos_step_status(jobject o){return o&&o->kind==TOS_STEP?o->value.step.status:-1;}
int tos_step_task(jobject o){return o&&o->kind==TOS_STEP?o->value.step.task:0;}
jobject tos_step_message(jobject o){return o&&o->kind==TOS_STEP?o->value.step.message:NULL;}
jobject tos_step_method(jobject o){return o&&o->kind==TOS_STEP?o->value.step.method:NULL;}
int64_t tos_step_metric(jobject o,int n){return o&&o->kind==TOS_STEP&&n>=0&&n<7?o->value.step.metrics[n]:0;}
size_t tos_shim_objects(void){return live_objects;}
void tos_set_host_callback(TosHostCallback callback){host_callback=callback;}

static jclass find_class(JNIEnv *e,const char *name){
    (void)e;for(int i=0;i<C_COUNT;i++)if(!strcmp(name,class_names[i])){
        classes[i].refs=UINT_MAX;classes[i].kind=TOS_CLASS;classes[i].value.integer=i;return &classes[i];
    }
    return reject();
}
static jobject global_ref(JNIEnv *e,jobject o){(void)e;retain(o);return o;}
static void delete_global(JNIEnv *e,jobject o){(void)e;release(o);}
static jint push_frame(JNIEnv *e,jint capacity){(void)e;(void)capacity;return tos_frame_push();}
static jobject pop_frame(JNIEnv *e,jobject result){(void)e;retain(result);tos_frame_pop();jobject out=local(result);release(result);return out;}
static jmethodID method(JNIEnv *e,jclass cls,const char *name,const char *signature){
    (void)e;(void)cls;(void)signature;
    static const char *names[]={"<init>","put","entrySet","iterator","hasNext","next","getKey","getValue","add","get","size","longValue","doubleValue","booleanValue","call"};
    for(int i=0;i<(int)(sizeof(names)/sizeof(names[0]));i++)if(!strcmp(name,names[i]))return &methods[i];
    reject();return NULL;
}
static jobject new_object(JNIEnv *e,jclass cls,jmethodID id,...){
    (void)e;(void)id;if(!cls)return reject();
    va_list args;va_start(args,id);jobject out=NULL;
    switch((int)cls->value.integer){
    case C_LONG:out=tos_value_int(va_arg(args,jlong));break;
    case C_DOUBLE:out=tos_value_double(va_arg(args,jdouble));break;
    case C_BOOL:out=tos_value_bool(va_arg(args,int));break;
    case C_LINKED_MAP:out=tos_value_map();break;
    case C_ARRAY_LIST:out=tos_value_array(0);break;
    case C_STEP:
        out=object_new(TOS_STEP);if(!out)break;
        out->value.step.task=va_arg(args,int);out->value.step.status=va_arg(args,int);
        out->value.step.message=va_arg(args,jobject);retain(out->value.step.message);
        out->value.step.metrics[0]=va_arg(args,jlong);out->value.step.metrics[1]=va_arg(args,jlong);
        out->value.step.metrics[2]=va_arg(args,jlong);out->value.step.metrics[3]=va_arg(args,int);
        out->value.step.method=va_arg(args,jobject);retain(out->value.step.method);
        out->value.step.metrics[4]=va_arg(args,jlong);out->value.step.metrics[5]=va_arg(args,jlong);break;
    default:reject();break;
    }va_end(args);return out;
}
static char *ascii(jobject o){
    if(!o||o->kind!=TOS_STRING||o->count>1024)return NULL;
    char *s=malloc((size_t)o->count+1);if(!s)return NULL;
    for(int i=0;i<o->count;i++){if(o->value.chars[i]>127){free(s);return NULL;}s[i]=(char)o->value.chars[i];}
    s[o->count]=0;return s;
}
#ifdef __EMSCRIPTEN__
EM_JS(uintptr_t, javascript_host, (const char *name, uintptr_t values, int task), {
    try { return Module['tosHost'](UTF8ToString(name), values, task); }
    catch (error) { Module['_tos_host_exception'](); return 0; }
});
#endif
static jobject call_object(JNIEnv *e,jobject o,jmethodID id,...){
    (void)e;if(!o||!id)return reject();va_list args;va_start(args,id);jobject out=NULL;
    switch(id->id){
    case M_PUT:{jobject key=va_arg(args,jobject),value=va_arg(args,jobject);
        for(int i=0;i<o->count;i++)if(same_string(o->value.pairs[i].key,key)){out=local(o->value.pairs[i].value);break;}
        tos_map_set(o,key,value);break;}
    case M_ENTRIES:out=local(o);break;
    case M_ITERATOR:out=object_new(TOS_ITERATOR);if(out){out->value.cursor.owner=o;retain(o);}break;
    case M_NEXT:
        if(o->kind!=TOS_ITERATOR||o->value.cursor.position>=o->value.cursor.owner->count){reject();break;}
        out=object_new(TOS_ENTRY);if(out){out->value.cursor.owner=o->value.cursor.owner;retain(out->value.cursor.owner);out->value.cursor.position=o->value.cursor.position++;}break;
    case M_KEY:case M_VALUE:
        if(o->kind!=TOS_ENTRY){reject();break;}
        out=local(id->id==M_KEY?tos_value_key(o->value.cursor.owner,o->value.cursor.position):tos_value_get(o->value.cursor.owner,o->value.cursor.position));break;
    case M_GET:out=local(tos_value_get(o,va_arg(args,int)));break;
    case M_CALL:{
        jobject name=va_arg(args,jobject),values=va_arg(args,jobject);int task=va_arg(args,int);
        char *text=ascii(name);if(!text){reject();break;}
#ifdef __EMSCRIPTEN__
        out=host_callback?host_callback(text,values,task):(jobject)javascript_host(text,(uintptr_t)values,task);
#else
        if(host_callback)out=host_callback(text,values,task);else reject();
#endif
        free(text);break;}
    default:reject();break;
    }va_end(args);return out;
}
static jboolean call_bool(JNIEnv *e,jobject o,jmethodID id,...){
    (void)e;if(!o||!id){reject();return 0;}
    if(id->id==M_BOOL)return o->value.integer!=0;
    if(id->id==M_HAS_NEXT)return o->kind==TOS_ITERATOR&&o->value.cursor.position<o->value.cursor.owner->count;
    if(id->id==M_ADD&&o->kind==TOS_ARRAY){
        va_list args;va_start(args,id);jobject value=va_arg(args,jobject);va_end(args);
        if(o->count==o->capacity){int capacity=o->capacity?o->capacity*2:16;
            if(capacity>1048576){reject();return 0;}jobject *items=realloc(o->value.items,(size_t)capacity*sizeof(jobject));
            if(!items){reject();return 0;}o->value.items=items;o->capacity=capacity;}
        retain(value);o->value.items[o->count++]=value;return 1;
    }reject();return 0;
}
static jint call_int(JNIEnv *e,jobject o,jmethodID id,...){(void)e;if(o&&id&&id->id==M_SIZE)return o->count;reject();return 0;}
static jlong call_long(JNIEnv *e,jobject o,jmethodID id,...){(void)e;(void)id;return tos_value_i64(o);}
static jdouble call_double(JNIEnv *e,jobject o,jmethodID id,...){(void)e;(void)id;return tos_value_f64(o);}
static jboolean instance_of(JNIEnv *e,jobject o,jclass cls){
    (void)e;if(!o||!cls)return 0;
    switch((int)cls->value.integer){
    case C_OBJECT:return 1;case C_STRING:return o->kind==TOS_STRING;case C_BOOL:return o->kind==TOS_BOOL;
    case C_LONG:return o->kind==TOS_INT;case C_DOUBLE:return o->kind==TOS_DOUBLE;
    case C_NUMBER:return o->kind==TOS_INT||o->kind==TOS_DOUBLE;
    case C_MAP:case C_LINKED_MAP:return o->kind==TOS_MAP;
    case C_LIST:case C_ARRAY_LIST:return o->kind==TOS_ARRAY;
    default:return 0;}
}
static jclass object_class(JNIEnv *e,jobject o){(void)o;return find_class(e,class_names[C_HOST]);}
static jboolean exception_check(JNIEnv *e){(void)e;return exception_pending!=0;}
static void exception_clear(JNIEnv *e){(void)e;exception_pending=0;}
static jstring new_string(JNIEnv *e,const jchar *s,jsize n){(void)e;return tos_value_string(s,n);}
static jstring new_utf(JNIEnv *e,const char *s){
    (void)e;size_t n=strlen(s);if(n>1048576)return reject();uint16_t *buffer=malloc((n+1)*sizeof(uint16_t));
    if(!buffer)return reject();size_t count=0;
    for(size_t i=0;i<n;){uint32_t c=(unsigned char)s[i++];
        if(c>=0xc0&&c<0xe0&&i<n){c=((c&31)<<6)|((unsigned char)s[i++]&63);}
        else if(c>=0xe0&&c<0xf0&&i+1<n){c=((c&15)<<12)|(((unsigned char)s[i]&63)<<6)|((unsigned char)s[i+1]&63);i+=2;}
        else if(c>=128){free(buffer);return reject();}buffer[count++]=(uint16_t)c;
    }jobject result=tos_value_string(buffer,(int)count);free(buffer);return result;
}
static const jchar *string_chars(JNIEnv *e,jstring s,jboolean *copy){(void)e;if(copy)*copy=0;return tos_value_chars(s);}
static void release_chars(JNIEnv *e,jstring s,const jchar *chars){(void)e;(void)s;(void)chars;}
static jsize string_length(JNIEnv *e,jstring s){(void)e;return tos_value_length(s);}
static void string_region(JNIEnv *e,jstring s,jsize start,jsize length,jchar *out){
    (void)e;if(!s||s->kind!=TOS_STRING||start<0||length<0||start>s->count-length){reject();return;}
    memcpy(out,s->value.chars+start,(size_t)length*sizeof(jchar));
}
static jobjectArray new_array(JNIEnv *e,jsize n,jclass cls,jobject initial){
    (void)e;(void)cls;jobject result=tos_value_array(n);if(result&&initial)for(int i=0;i<n;i++)tos_array_set(result,i,initial);return result;
}
static jsize array_length(JNIEnv *e,jobjectArray o){(void)e;return tos_value_length(o);}
static jobject array_get(JNIEnv *e,jobjectArray o,jsize n){(void)e;return local(tos_value_get(o,n));}
static void array_set(JNIEnv *e,jobjectArray o,jsize n,jobject value){(void)e;tos_array_set(o,n,value);}
static const struct JNINativeInterface_ interface={
    find_class,global_ref,delete_global,delete_local,push_frame,pop_frame,method,new_object,
    call_object,call_bool,call_int,call_long,call_double,instance_of,object_class,exception_check,exception_clear,
    new_string,new_utf,string_chars,release_chars,string_length,string_region,new_array,array_length,array_get,array_set
};
static JNIEnv environment=&interface;
JNIEnv *tos_environment(void){return &environment;}
