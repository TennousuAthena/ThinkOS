/* MIT. Exercise the real bridge through the simulator's JNI adapter. */
#include "shim.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

uintptr_t tos_vm_create(void);
jobject tos_vm_start(uintptr_t,jobject,jobject,jobject,jobject);
jobject tos_vm_resume(uintptr_t,int,jobject);
void tos_vm_bind(uintptr_t);
void tos_vm_cancel(uintptr_t,int);
int64_t tos_vm_memory(uintptr_t);
void tos_vm_destroy(uintptr_t);
static jobject state;
static int waiting_task, deny_time;
static jstring text(const char *s){JNIEnv *e=tos_environment();return (*e)->NewStringUTF(e,s);}
static jobject host(const char *method,jobject args,int task){
    JNIEnv *e=tos_environment();
    if(!strcmp(method,"config")){
        jobject result=tos_value_map();uint16_t city[]={0x9999,0x6e2f,0xd83d,0xde00};
        tos_map_set(result,text("city"),tos_value_string(city,4));return result;
    }
    if(!strcmp(method,"time.now")){if(deny_time){tos_host_exception();return NULL;}return tos_value_int(1704067200000LL);}
    if(!strcmp(method,"state.set")){
        jobject next=(*e)->NewGlobalRef(e,tos_value_get(args,0));
        if(state)(*e)->DeleteGlobalRef(e,state);state=next;return NULL;
    }
    if(!strcmp(method,"store.get"))return state;
    if(!strcmp(method,"module"))return text("return {answer=42}");
    if(!strcmp(method,"sleep")||!strcmp(method,"http.get")){waiting_task=task;return NULL;}
    if(!strcmp(method,"timer.add")||!strcmp(method,"log"))return NULL;
    tos_host_exception();return NULL;
}
static int execute(uintptr_t vm,const char *source,const char *entry,jobject args,int expected){
    jobject step=tos_vm_start(vm,source?text(source):NULL,text("conformance"),entry?text(entry):NULL,args);
    if(tos_step_status(step)!=expected){
        fprintf(stderr,"expected %d, got %d for %.100s\n",expected,tos_step_status(step),source?source:entry);
        abort();
    }return tos_step_task(step);
}
static int event(uintptr_t vm,const char *name,int expected){
    jobject args=tos_value_array(1);tos_array_set(args,0,text(name));return execute(vm,NULL,"dispatch",args,expected);
}
static char *read_file(const char *path){
    FILE *file=fopen(path,"rb");assert(file);assert(!fseek(file,0,SEEK_END));long size=ftell(file);assert(size>=0&&size<262144);
    rewind(file);char *data=calloc((size_t)size+1,1);assert(data);assert(fread(data,1,(size_t)size,file)==(size_t)size);fclose(file);return data;
}
int main(int argc,char **argv){
    assert(argc==2);char *bootstrap=read_file(argv[1]);tos_set_host_callback(host);assert(!tos_frame_push());
    uintptr_t vm=tos_vm_create();assert(vm);execute(vm,bootstrap,NULL,NULL,0);free(bootstrap);tos_vm_bind(vm);
    execute(vm,"assert(tos.config.city=='\\u{9999}\\u{6e2f}\\u{1f600}');assert(require('sample').answer==42)",NULL,NULL,0);
    execute(vm,"local s='[null,{},[],\"x\\\\u0000y\",9007199254740993,9223372036854775807,-9223372036854775808]';assert(tos.json.encode(tos.json.decode(s))==s)",NULL,NULL,0);
    execute(vm,"assert(io==nil and os==nil and debug==nil and package==nil and _host==nil);assert(not pcall(function()tos.config.city='x'end))",NULL,NULL,0);
    execute(vm,"tos.state.set{integer=9007199254740993,i=1,f=1.0,object={},array=tos.json.decode('[]'),nothing=tos.json.null};local s=tos.store.get('x');assert(math.type(s.integer)=='integer' and s.integer==9007199254740993);assert(math.type(s.i)=='integer' and math.type(s.f)=='float');assert(tos.json.encode(s.array)=='[]' and tos.json.encode(s.object)=='{}' and s.nothing==tos.json.null)",NULL,NULL,0);
    execute(vm,"return tos.json.decode('[1,]')",NULL,NULL,2);
    execute(vm,"return tos.json.decode('9223372036854775808')",NULL,NULL,2);
    execute(vm,"tos.on('wait',function()local r,e=tos.http.get('https://example.test');assert(r.body=='ok' and e==nil);tos.timer.sleep(.001);tos.state.set{done=true}end)",NULL,NULL,0);
    int task=event(vm,"wait",1);assert(task==waiting_task);
    jobject response=tos_value_map();tos_map_set(response,text("body"),text("ok"));jobject args=tos_value_array(2);tos_array_set(args,0,response);
    jobject step=tos_vm_resume(vm,task,args);assert(tos_step_status(step)==1&&tos_step_task(step)==task);
    step=tos_vm_resume(vm,task,tos_value_array(0));assert(tos_step_status(step)==0);
    task=event(vm,"wait",1);tos_vm_cancel(vm,task);assert(tos_step_status(tos_vm_resume(vm,task,args))==2);
    deny_time=1;execute(vm,"return tos.time.now()",NULL,NULL,2);deny_time=0;
    execute(vm,"assert(tos.time.now()==1704067200000)",NULL,NULL,0);
    execute(vm,"while true do pcall(function()while true do end end)end",NULL,NULL,3);
    execute(vm,"return string.rep('a',200):match('^a*a*a*a*a*b')",NULL,NULL,3);
    execute(vm,"local x=string.rep('x',8*1024*1024)",NULL,NULL,4);
    size_t before=tos_shim_objects();
    for(int i=0;i<500;i++){
        assert(!tos_frame_push());
        execute(vm,"assert(tos.time.now()==1704067200000);assert(tos.config.city=='\\u{9999}\\u{6e2f}\\u{1f600}')",NULL,NULL,0);
        tos_frame_pop();assert(tos_shim_objects()==before);
    }
    assert(tos_vm_memory(vm)>0&&tos_vm_memory(vm)<4*1024*1024);
    tos_vm_destroy(vm);JNIEnv *e=tos_environment();if(state)(*e)->DeleteGlobalRef(e,state);state=NULL;
    tos_frame_pop();assert(tos_shim_objects()==0);
    puts("PASS simulator JNI bridge: bootstrap, unicode, int64, null/containers, async/cancel, errors, budgets, reference lifetime");
    return 0;
}
