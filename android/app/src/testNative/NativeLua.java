package moe.kiwi.thinkos.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Runs the exact production JNI scheduler and codec, without Android. */
public class NativeLua {
    public static class Step {
        public final int taskId, status;
        public final String message;
        public final long cpuMicros, wallMicros;
        public Step(int id,int status,String message,long cpu,long wall) {
            taskId=id;this.status=status;this.message=message;cpuMicros=cpu;wallMicros=wall;
        }
        public String toString(){return status+":"+message+" CPU="+cpuMicros+"us wall="+wallMicros+"us";}
    }
    private native long create();
    private native Step start(long h,String entry,Object[] args,String source,String label);
    private native Step resume(long h,int id,Object[] args);
    private native void bind(long h);
    private native void cancel(long h,int id);
    private native long memory(long h);
    private native void destroy(long h);
    private long handle;
    private Object state;
    private Object[] timer;
    private int awaited;
    private boolean slowTime;
    private int burnMillis;
    public Object call(String method,Object[] args,int taskId) {
        switch(method) {
            case "config": return Map.of("city","香港😀","nested",Map.of("x",1L));
            case "time.now":
                if(burnMillis>0)burnCpu(burnMillis);
                if(slowTime) try{Thread.sleep(250);}catch(InterruptedException e){throw new RuntimeException(e);}
                return 1704067200000L;
            case "state.set": state=args[0];return null;
            case "timer.add": timer=args;return null;
            case "store.get": return Map.of("a",1L);
            case "module": return "return {answer=42}";
            case "sleep": case "http.get": awaited=taskId;return null;
            default:return null;
        }
    }
    /** Busy-waits on thread CPU time, like a trusted host call doing real work (ICU data loading, say). */
    static void burnCpu(int millis){
        java.lang.management.ThreadMXBean bean=java.lang.management.ManagementFactory.getThreadMXBean();
        long end=bean.getCurrentThreadCpuTime()+millis*1_000_000L;
        while(bean.getCurrentThreadCpuTime()<end){}
    }
    Step run(String source){Step r=start(handle,null,null,source,"test");if(r.status!=0)throw new AssertionError(r);return r;}
    void fail(String source,int kind){Step r=start(handle,null,null,source,"bad");if(r.status!=kind)throw new AssertionError("Expected "+kind+", got "+r);}
    Step event(String name,Object...args){Object[] a=new Object[args.length+1];a[0]=name;System.arraycopy(args,0,a,1,args.length);return start(handle,"dispatch",a,null,null);}
    static void ok(boolean value,String text){if(!value)throw new AssertionError(text);System.out.println("PASS "+text);}
    public static void main(String[] args)throws Exception{
        System.load(args[0]);String bootstrap=Files.readString(Path.of(args[1]));
        NativeLua lua=new NativeLua();lua.handle=lua.create();lua.run(bootstrap);lua.bind(lua.handle);
        lua.run("assert(tos.config.city=='香港😀');tos.state.set({hello='香港😀',nested={a=true,b=false}});assert(require('sample').answer==42)");
        ok(((Map<?,?>)lua.state).get("hello").equals("香港😀"),"typed JNI Unicode and object values");
        lua.run("local text=[=[[null,{},[],\"x\\u0000y\",9007199254740993]]=];assert(tos.json.encode(tos.json.decode(text))==text);assert(tos.json.decode('null')==tos.json.null)");
        lua.fail("return tos.json.decode('[1,]')",2);
        lua.fail("local t={};t.x=t;return tos.json.encode(t)",2);
        lua.fail("return tos.json.encode({x=0/0})",2);
        ok(true,"native JSON null/empty arrays/int64/NUL and strict validation");
        lua.run("assert(not pcall(function()tos.config.city='x'end));assert(not pcall(function()rawset(tos.config,'city','x')end));assert(not pcall(function()setmetatable({},{__gc=function()end})end));assert(_host==nil and _await==nil and _json==nil and io==nil and os==nil and debug==nil and package==nil)");
        ok(true,"sealed configuration, finalizers and hidden host functions");

        lua.run("assert(('abc123'):match('(%d+)')=='123');assert(('a1b2'):gsub('%d','x')=='axbx');local n=0;for x in ('a1b2'):gmatch('%d')do n=n+1 end;assert(n==2)");
        lua.fail("return string.rep('a',200):match('^a*a*a*a*a*b')",3);
        lua.fail("while true do pcall(function()return string.rep('a',200):gsub('^a*a*a*a*a*b','x')end)end",3);
        ok(true,"native Lua patterns restored and backtracking is uncatchably budgeted");
        lua.run("tos.on('command',function(name)tos.state.set({command=name})end)");
        Step event=lua.event("command","typed-event");
        ok(event.status==0 && ((Map<?,?>)lua.state).get("command").equals("typed-event"),"cached function dispatch with typed arguments");
        lua.run("tos.on('command',function()local response,err=tos.http.get('https://example.test');assert(err==nil and response.body=='done');tos.timer.sleep(.01);tos.state.set({resumed=true})end)");
        Step wait=lua.event("command");
        ok(wait.status==1 && wait.taskId==lua.awaited,"async API yield has a retained task identity");
        Thread.sleep(220);
        Step again=lua.resume(lua.handle,wait.taskId,new Object[]{Map.of("body","done"),null});
        ok(again.status==1 && again.taskId==wait.taskId,"async continuation can suspend a second time");
        Step done=lua.resume(lua.handle,again.taskId,new Object[]{});
        ok(done.status==0 && ((Map<?,?>)lua.state).get("resumed").equals(true),"resume budgets reset after wall time spent awaiting IO");
        lua.run("tos.on('command',function()local ok,value=pcall(function()tos.timer.sleep(.01);return 'done'end);assert(ok and value=='done')end)");
        wait=lua.event("command");
        ok(wait.status==1 && lua.resume(lua.handle,wait.taskId,new Object[]{}).status==0,"normal async yield crosses protected pcall");
        lua.run("tos.on('command',function()local co=coroutine.create(function()tos.timer.sleep(.01)end);local ok,err=coroutine.resume(co);assert(not ok and err:find('yieldable',1,true))end)");
        ok(lua.event("command").status==0,"unmanaged child coroutines cannot orphan async requests");
        lua.run("tos.on('command',function()tos.timer.sleep(.01);while true do end end)");
        wait=lua.event("command");
        Step exhausted=lua.resume(lua.handle,wait.taskId,new Object[]{});
        ok(exhausted.status==3,"CPU budget enforced on each continuation");
        lua.run("tos.on('command',function()tos.timer.sleep(.01);tos.state.set({bad=true})end)");
        wait=lua.event("command");lua.cancel(lua.handle,wait.taskId);
        ok(lua.resume(lua.handle,wait.taskId,new Object[]{}).status==2,"cancelled async tasks cannot resurrect");
        lua.slowTime=true;lua.fail("return tos.time.now()",5);lua.slowTime=false;
        lua.run("assert(tos.time.now()==1704067200000)");
        ok(true,"200ms wall cap and recovery after blocked synchronous host");
        lua.burnMillis=70;
        lua.run("assert(tos.time.now()==1704067200000)");
        ok(true,"CPU spent inside one trusted host call is credited to the script (70ms > the 50ms script budget)");
        lua.burnMillis=12;
        lua.fail("for i=1,100 do tos.time.now() end",3);
        lua.burnMillis=0;
        ok(true,"host-call credit is capped, so a loop of host calls still hits the budget");
        lua.run("assert(tos.time.now()==1704067200000)");
        lua.run("tos.on('command',function()error('ordinary failure')end)");
        ok(lua.event("command").status==2,"ordinary script error classified separately from resource failure");
        lua.run("tos.timer.every(60,function()tos.state.set({tick=1})end,{align=true})");
        Step timer=lua.start(lua.handle,"timer",new Object[]{1L,true},null,null);
        ok(timer.status==0 && ((Map<?,?>)lua.state).get("tick").equals(1L),"cached timer callback");
        StringBuilder data=new StringBuilder("[");
        for(int i=0;i<1000;i++){if(i>0)data.append(',');data.append("{\"value\":").append(i).append(",\"text\":\"weather sample with unicode 香港\"}");}
        data.append(']');
        lua.run("tos.on('command',function(body)local v=tos.json.decode(body);assert(#v==1000 and v[1000].value==999)end)");
        Step large=lua.event("command",data.toString());
        ok(large.status==0,"large JSON C decoder ("+data.length()+" chars, CPU "+large.cpuMicros+"us)");
        for(int i=0;i<1000;i++)if(lua.event("command","[]").status!=2)throw new AssertionError("wrong malformed data handler");
        ok(lua.memory(lua.handle)<4*1024*1024,"generational GC bounds repeated event allocations");
        lua.destroy(lua.handle);
        ArrayList<NativeLua> all=new ArrayList<>();int rejected=0;
        for(int i=0;i<8;i++){
            NativeLua other=new NativeLua();other.handle=other.create();all.add(other);
            if(other.handle==0){rejected++;continue;}
            Step r=other.start(other.handle,null,null,"x={};for i=1,35 do x[i]=string.rep(tostring(i),50*1024)end","aggregate");
            if(r.status!=0)rejected++;
        }
        ok(rejected>0,"aggregate 24MB cap includes native JSON allocations");
        for(NativeLua other:all)if(other.handle!=0)other.destroy(other.handle);
    }
}
