"""Run production backend logic on a JVM with deterministic Android/Xposed test doubles.
No Android device, Gradle download, or system installation is required.
Usage: python backend-tests/run_tests.py --java-home <JDK directory>
"""
from pathlib import Path
import argparse, os, subprocess, shutil
from bridge_tests import run_bridge_tests
parser=argparse.ArgumentParser(); parser.add_argument('--java-home'); args=parser.parse_args()
project=Path(__file__).resolve().parents[1]
testroot=Path(__file__).resolve().parent
build=testroot/'build'; sources=build/'stubs'; classes=build/'classes'
sources.mkdir(parents=True,exist_ok=True); classes.mkdir(parents=True,exist_ok=True)
stubs={
'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){ return new Looper(); } }',
'android/os/Handler.java': r"""package android.os; import java.util.*; public class Handler {
public static long now=10000; static long serial; static final List<Task> tasks=new ArrayList<>();
static class Task { Runnable r; long time,id; Task(Runnable r,long time){this.r=r;this.time=time;id=serial++;} }
public Handler(Looper l){} public boolean post(Runnable r){return postDelayed(r,0);}
public boolean postDelayed(Runnable r,long d){tasks.add(new Task(r,now+d));return true;}
public void removeCallbacks(Runnable r){tasks.removeIf(t->t.r==r);} public static void reset(){tasks.clear();now=10000;serial=0;}
public static int pendingCount(){return tasks.size();}
public static void advance(long ms){long end=now+ms; while(true){ Task next=tasks.stream().filter(t->t.time<=end).min(Comparator.<Task>comparingLong(t->t.time).thenComparingLong(t->t.id)).orElse(null);if(next==null)break;tasks.remove(next);now=next.time;next.r.run(); }now=end; }
}""",
'android/os/HandlerThread.java': 'package android.os; public class HandlerThread extends Thread { public HandlerThread(String n){super(n);} public Looper getLooper(){return new Looper();} }',
'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long uptimeMillis(){return Handler.now;} }',
'android/os/Bundle.java': 'package android.os; import java.util.*; public class Bundle {private final Map<String,String> values=new HashMap<>(); public String getString(String key){return values.get(key);} public void putString(String key,String value){values.put(key,value);} }',
'android/content/SharedPreferences.java': 'package android.content; public interface SharedPreferences {String getString(String key,String fallback);Editor edit(); interface Editor {Editor putString(String key,String value);void apply();} }',
'android/content/Context.java': r'''package android.content; import java.util.*; public class Context {
public static final int MODE_PRIVATE=0; private final Map<String,MemoryPreferences> prefs=new HashMap<>();
public ContentResolver getContentResolver(){return new ContentResolver();}
public SharedPreferences getSharedPreferences(String name,int mode){return memoryPreferences(name);}
public MemoryPreferences memoryPreferences(String name){return prefs.computeIfAbsent(name,k->new MemoryPreferences());}
public static class MemoryPreferences implements SharedPreferences {
public final Map<String,String> values=new HashMap<>();public int applies;
public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
public Editor edit(){return new Editor(){private final Map<String,String> pending=new HashMap<>();public Editor putString(String key,String value){pending.put(key,value);return this;}public void apply(){values.putAll(pending);applies++;}};}
}
}''',
'android/net/Uri.java': 'package android.net; public final class Uri {private final String value;private Uri(String value){this.value=value;}public static Uri parse(String value){return new Uri(value);}public String toString(){return value;} }',
'android/content/ContentResolver.java': r'''package android.content; import android.os.Bundle; import android.database.ContentObserver; public class ContentResolver {
public static int registrationAttempts, registrations, snapshotReads,changes; public static boolean failRegistration, failSnapshot,throwSnapshot; public static String snapshot,lastCallUri,lastNotifyUri,lastMethod; static ContentObserver registeredObserver;
public static void reset(){registrationAttempts=registrations=snapshotReads=changes=0;failRegistration=failSnapshot=throwSnapshot=false;snapshot=lastCallUri=lastNotifyUri=lastMethod=null;registeredObserver=null;}
public void registerContentObserver(Object u,boolean b,ContentObserver o){registrationAttempts++;if(failRegistration)throw new IllegalStateException("user locked");registrations++;registeredObserver=o;}
public Bundle call(Object u,String m,String a,Bundle e){snapshotReads++;lastCallUri=u.toString();lastMethod=m;if(throwSnapshot)throw new IllegalStateException("provider unavailable");if(failSnapshot)return null;Bundle result=new Bundle();result.putString("snapshot",snapshot);return result;}
public void notifyChange(Object u,Object observer){changes++;lastNotifyUri=u.toString();}
public static void fireChange(){if(registeredObserver!=null)registeredObserver.dispatchChange();}
}''',
'android/database/ContentObserver.java': 'package android.database; import android.os.Handler; public class ContentObserver {private final Handler handler;public ContentObserver(Handler h){handler=h;} public void onChange(boolean b){} public void dispatchChange(){handler.post(()->onChange(false));} }',
'android/util/Log.java': 'package android.util; public class Log {public static int i(String t,String m){return 0;} public static int w(String t,String m){return 0;} public static int w(String t,String m,Throwable e){e.printStackTrace();return 0;} }',
'android/view/InputDevice.java': 'package android.view; public class InputDevice {String n; public InputDevice(String n){this.n=n;} public String getName(){return n;} }',
'android/view/KeyEvent.java': 'package android.view; public class KeyEvent {public static final int KEYCODE_PAGE_DOWN=93,KEYCODE_PAGE_UP=92,ACTION_DOWN=0,ACTION_UP=1; int a,c,r;boolean cancel; InputDevice d;public KeyEvent(int a,int c,int r,boolean cancel,InputDevice d){this.a=a;this.c=c;this.r=r;this.cancel=cancel;this.d=d;} public int getKeyCode(){return c;} public int getAction(){return a;} public int getRepeatCount(){return r;} public InputDevice getDevice(){return d;} public boolean isCanceled(){return cancel;} }',
'de/robv/android/xposed/XC_MethodHook.java': 'package de.robv.android.xposed; public class XC_MethodHook {public static class MethodHookParam {public Object[] args=new Object[0];public Object result;public void setResult(Object o){result=o;} }protected void beforeHookedMethod(MethodHookParam p){} protected void afterHookedMethod(MethodHookParam p){} }',
'de/robv/android/xposed/XposedBridge.java': 'package de.robv.android.xposed; public class XposedBridge {public static void log(String s){} public static void hookAllMethods(Class<?> c,String m,XC_MethodHook h){} }',
'de/robv/android/xposed/XposedHelpers.java': 'package de.robv.android.xposed; public class XposedHelpers {public static void findAndHookMethod(String c,ClassLoader l,String n,Object... args){} public static Class<?> findClass(String n,ClassLoader l){return Object.class;} public static Object callMethod(Object o,String n,Object...args){return null;} public static Object getObjectField(Object o,String n){return null;} }',
'xiaomismartpen/plus/ActionRunner.java': 'package xiaomismartpen.plus; import java.util.*; import android.content.Context; final class ActionRunner {static final List<String> actions=new ArrayList<>(); static void setSystemClassLoader(ClassLoader c){} static void run(Context c,String a,int k){actions.add(a);} }',
}
stubs['android/os/SystemClock.java']=stubs['android/os/SystemClock.java'].replace('public static long uptimeMillis()', 'public static long elapsedRealtime(){return Handler.now;} public static long uptimeMillis()')
stubs['android/content/Context.java']=stubs['android/content/Context.java'].replace('public static final int MODE_PRIVATE=0;', 'public static final int MODE_PRIVATE=0; public static final String INPUT_SERVICE="input"; public Context createDeviceProtectedStorageContext(){return this;} public Object getSystemService(String name){return new android.hardware.input.InputManager();}')
stubs['android/content/SharedPreferences.java']=stubs['android/content/SharedPreferences.java'].replace('void apply();','void apply();boolean commit();')
stubs['android/content/Context.java']=stubs['android/content/Context.java'].replace('public void apply(){values.putAll(pending);applies++;}', 'public void apply(){values.putAll(pending);applies++;}public boolean commit(){apply();return true;}')
stubs['android/hardware/input/InputManager.java']='package android.hardware.input; import android.os.Handler; public class InputManager {public interface InputDeviceListener {void onInputDeviceAdded(int id);void onInputDeviceRemoved(int id);void onInputDeviceChanged(int id);}public int[] getInputDeviceIds(){return new int[0];}public android.view.InputDevice getInputDevice(int id){return null;}public void registerInputDeviceListener(InputDeviceListener l, Handler h) {}}'
stubs['android/os/IBinder.java']='package android.os; public interface IBinder {}'
stubs['android/os/Build.java']=r'''package android.os; public final class Build {
public static String FINGERPRINT="oneplus/test/device:17/release-keys",DISPLAY="",MANUFACTURER="",BRAND="";
}'''
stubs['android/os/SystemProperties.java']=r'''package android.os; import java.util.*; public final class SystemProperties {
public static final Map<String,String> values=new LinkedHashMap<>(); public static boolean failGet;
public static String get(String name){if(failGet)throw new SecurityException("property access denied");return values.getOrDefault(name,"");}
public static void reset(){values.clear();failGet=false;}
}'''
stubs['android/view/InputEvent.java']='package android.view; public class InputEvent {public int recycleCount; public void recycle(){recycleCount++;}}'
stubs['de/robv/android/xposed/XC_MethodHook.java']=r'''package de.robv.android.xposed; public class XC_MethodHook {
public static class Unhook {}
public static class MethodHookParam {public Object[] args=new Object[0];public Object result,thisObject;public java.lang.reflect.Member method;public void setResult(Object value){result=value;}public Object getResult(){return result;}}
protected void beforeHookedMethod(MethodHookParam p){} protected void afterHookedMethod(MethodHookParam p){}
}'''
stubs['de/robv/android/xposed/XposedBridge.java']=r'''package de.robv.android.xposed; public class XposedBridge {
public static void log(String text){}public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> type,String name,XC_MethodHook hook){XposedHelpers.recordHook(type,name,hook);return java.util.Collections.singleton(new XC_MethodHook.Unhook());}
}'''
stubs['de/robv/android/xposed/XposedHelpers.java']=r'''package de.robv.android.xposed; import java.util.*; import java.lang.reflect.*; public class XposedHelpers {
public static final Map<String,XC_MethodHook> hooks=new LinkedHashMap<>();
public static final Map<String,List<String>> hookedTypes=new LinkedHashMap<>();private static final Map<Class<?>,String> classNames=new IdentityHashMap<>();
public static void findAndHookMethod(String type,ClassLoader loader,String name,Object... args){record(type,name,(XC_MethodHook)args[args.length-1]);}
public static void findAndHookMethod(Class<?> type,String name,Object... args){recordHook(type,name,(XC_MethodHook)args[args.length-1]);}
public static void recordHook(Class<?> type,String name,XC_MethodHook hook){record(classNames.getOrDefault(type,type.getName()),name,hook);}
private static void record(String type,String name,XC_MethodHook hook){hooks.put(name,hook);hookedTypes.computeIfAbsent(name,key->new ArrayList<>()).add(type);}
public static Class<?> findClass(String name,ClassLoader loader){Class<?> type;try{type=Class.forName(name,true,loader);}catch(ClassNotFoundException absent){type=Object.class;}classNames.put(type,name);return type;}
public static Object callStaticMethod(Class<?> type,String name,Object... args){try{for(Method method:type.getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){method.setAccessible(true);return method.invoke(null,args);}throw new NoSuchMethodException(name);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}}
public static Object callMethod(Object target,String name,Object... args){
try {for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())for(Method method:type.getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){method.setAccessible(true);return method.invoke(target,args);}throw new NoSuchMethodException(name);}
catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}}
public static Object getObjectField(Object target,String name){
try {for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(NoSuchFieldException absent){}throw new NoSuchFieldException(name);}
catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}}
}'''
stubs['xiaomismartpen/plus/SystemPenBridge.java']='package xiaomismartpen.plus; import android.content.Context; final class SystemPenBridge {static void configure(Context c, PenConfig p){}}'
stubs['android/view/KeyEvent.java']=stubs['android/view/KeyEvent.java'].replace('KEYCODE_PAGE_DOWN=93,', 'KEYCODE_BUTTON_1=188,KEYCODE_BUTTON_2=189,KEYCODE_PAGE_DOWN=93,')
stubs['xiaomismartpen/plus/ActionRunner.java']=stubs['xiaomismartpen/plus/ActionRunner.java'].replace('actions.add(a);', 'actions.add(a);lastCode=k;').replace('static final List<String>', 'static int lastCode;static final List<String>')
stubs['android/provider/Settings.java']=r'''package android.provider; import java.util.*; import android.content.ContentResolver; public final class Settings {
public static final class Global {
public static final Map<String,String> values=new HashMap<>(); public static boolean failRead,failWrite; public static int writes;
public static void reset(){values.clear();failRead=failWrite=false;writes=0;}
public static String getString(ContentResolver r,String key){if(failRead)throw new IllegalStateException("settings unavailable");return values.get(key);}
public static boolean putString(ContentResolver r,String key,String value){if(failWrite)throw new IllegalStateException("settings write failed");values.put(key,value);writes++;return true;}
}}'''
stubs['android/view/InputDevice.java']=r'''package android.view; public class InputDevice {
public static final int SOURCE_TOUCHSCREEN=0x1002;private final String name;private final int id;public InputDevice(String name){this(name,42);}public InputDevice(String name,int id){this.name=name;this.id=id;}
public String getName(){return name;}public int getId(){return id;}}
'''
stubs['android/view/KeyEvent.java']=r'''package android.view; public class KeyEvent extends InputEvent {
public static final int KEYCODE_BUTTON_1=188,KEYCODE_BUTTON_2=189,KEYCODE_PAGE_DOWN=93,KEYCODE_PAGE_UP=92,KEYCODE_MEDIA_PLAY_PAUSE=85,KEYCODE_BACK=4,KEYCODE_HOME=3,KEYCODE_APP_SWITCH=187,ACTION_DOWN=0,ACTION_UP=1,FLAG_KEEP_TOUCH_MODE=4,FLAG_CANCELED=0x20;
private int action,code,repeat,meta,scan,flags,source,display;private long downTime,eventTime;private InputDevice device;
public KeyEvent(int action,int code,int repeat,boolean canceled,InputDevice device){this(android.os.Handler.now,android.os.Handler.now,action,code,repeat,0,device,0,canceled?FLAG_CANCELED:0,0x101,0);}
public KeyEvent(long down,long time,int action,int code,int repeat,int meta,InputDevice device,int scan,int flags,int source,int display){this.downTime=down;this.eventTime=time;this.action=action;this.code=code;this.repeat=repeat;this.meta=meta;this.device=device;this.scan=scan;this.flags=flags;this.source=source;this.display=display;}
public KeyEvent(long down,long time,int action,int code,int repeat){this(down,time,action,code,repeat,0,null,0,0,0x101,0);}
public KeyEvent(long down,long time,int action,int code,int repeat,int meta,int deviceId,int scan,int flags,int source){this(down,time,action,code,repeat,meta,new InputDevice("Xiaomi Smart Pen Keyboard",deviceId),scan,flags,source,0);}
public KeyEvent(KeyEvent other){this(other.downTime,other.eventTime,other.action,other.code,other.repeat,other.meta,other.device,other.scan,other.flags,other.source,other.display);}
public int getKeyCode(){return code;}public int getAction(){return action;}public int getRepeatCount(){return repeat;}public InputDevice getDevice(){return device;}public boolean isCanceled(){return (flags&FLAG_CANCELED)!=0;}
public long getDownTime(){return downTime;}public long getEventTime(){return eventTime;}public int getDeviceId(){return device==null?-1:device.getId();}public int getScanCode(){return scan;}public int getFlags(){return flags;}public int getSource(){return source;}public int getMetaState(){return meta;}public int getDisplayId(){return display;}
public void overwriteCodeForTest(int replacement){code=replacement;}public void setDisplayId(int value){display=value;}
}'''
# Capture the original events requested by the production controller. Mapping and Android input
# injection belong to the production ActionRunner and require the separate device receiver test.
stubs['xiaomismartpen/plus/ActionRunner.java']=r'''package xiaomismartpen.plus; import java.util.*; import android.content.Context; import android.view.KeyEvent; final class ActionRunner {
static final List<String> actions=new ArrayList<>();static final List<KeyEvent> forwarded=new ArrayList<>();static final List<KeyEvent> cancelled=new ArrayList<>();static int lastCode;static boolean failForward,failCancel;static int forwardAttempts,cancelAttempts;
static void setSystemClassLoader(ClassLoader loader){}static void run(Context context,String action,int code){actions.add(action);lastCode=code;}
static void resetPenDevice(Context context,int deviceId){}
static void forwardPenEvent(Context context,KeyEvent event){forwardAttempts++;if(failForward)throw new IllegalStateException("test input rejection");forwarded.add(new KeyEvent(event));}
static void cancelPenEvent(Context context,KeyEvent event){cancelAttempts++;if(failCancel)throw new IllegalStateException("test cancellation rejection");cancelled.add(new KeyEvent(event));}
}'''
for name,content in stubs.items():
    p=sources/name; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(content,encoding='utf-8')
java_home=Path(args.java_home) if args.java_home else None
executable_suffix = '.exe' if os.name == 'nt' else ''
javac=str(java_home/'bin'/('javac'+executable_suffix)) if java_home else shutil.which('javac')
java=str(java_home/'bin'/('java'+executable_suffix)) if java_home else shutil.which('java')
if not java or not javac: raise SystemExit('A JDK is required; pass --java-home if it is not on PATH.')
prod=project/'app'/'src'/'main'/'java'/'xiaomismartpen'/'plus'
# Compile only this run's doubles; stale generated package paths from earlier runs are ignored.
files=[str(sources/name) for name in stubs]+[str(prod/'PenConfig.java'),str(prod/'PenKeyController.java'),str(prod/'ConfigStore.java'),str(prod/'RomProfile.java'),str(testroot/'BackendTests.java'),str(testroot/'ConfigStoreTests.java'),str(testroot/'RomProfileTests.java')]
subprocess.run([javac,'-encoding','UTF-8','-d',str(classes),*files],check=True)
subprocess.run([java,'-cp',str(classes),'xiaomismartpen.plus.BackendTests'],check=True)
subprocess.run([java,'-cp',str(classes),'xiaomismartpen.plus.ConfigStoreTests'],check=True)
rom_cases={
    'color-property':'COLOROS','oplus-property':'COLOROS','oplusrom-property':'COLOROS',
    'opporom-property':'COLOROS','port-mixed':'COLOROS','oneplus-fingerprint':'COLOROS',
    'oppo-brand':'COLOROS','color-display':'COLOROS','oneplus-manufacturer-miui':'COLOROS',
    'oplus-brand':'COLOROS','oppo-fingerprint':'COLOROS','property-denied-color':'COLOROS','hyper-property':'HYPEROS',
    'hyper-code':'HYPEROS','miui-property':'HYPEROS','miui-code':'HYPEROS',
    'hyper-fingerprint':'HYPEROS','miui-display':'HYPEROS','unknown':'UNKNOWN',
    'property-denied':'UNKNOWN','property-denied-fingerprint':'HYPEROS','null-build':'UNKNOWN',
}
# RomProfile caches once per process. Isolated JVMs exercise real first-load detection.
for case,expected in rom_cases.items():
    subprocess.run([java,'-cp',str(classes),'xiaomismartpen.plus.RomProfileTests',case,expected],check=True)

# Compile the real sender in a separate classpath. Only the native endpoint and unrelated Android
# action dependencies are replaced; this validates outgoing fields and native call arguments.
sender_sources=build/'sender-stubs'; sender_classes=build/'sender-classes'
sender_sources.mkdir(parents=True,exist_ok=True);sender_classes.mkdir(parents=True,exist_ok=True)
sender_stubs={name:content for name,content in stubs.items() if name!='xiaomismartpen/plus/ActionRunner.java'}
sender_stubs.update({
'android/content/Intent.java':'package android.content; public class Intent {public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000;public Intent addFlags(int flags){return this;}}',
'android/content/pm/PackageManager.java':'package android.content.pm; public class PackageManager {public android.content.Intent getLaunchIntentForPackage(String name){return null;}}',
'android/content/res/Resources.java':'package android.content.res; public class Resources {public static Resources getSystem(){return new Resources();}public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}}',
'android/util/DisplayMetrics.java':'package android.util; public class DisplayMetrics {public int widthPixels=2880,heightPixels=1800;}',
'android/media/AudioManager.java':'package android.media; public class AudioManager {public static final int ADJUST_RAISE=1,ADJUST_LOWER=-1,STREAM_MUSIC=3,FLAG_SHOW_UI=1;public void adjustStreamVolume(int stream,int direction,int flags){}}',
'android/view/MotionEvent.java':r'''package android.view; public class MotionEvent extends InputEvent {
public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2;private long down,time;private int action,source;private float x,y;
public static MotionEvent obtain(long down,long time,int action,float x,float y,int meta){MotionEvent event=new MotionEvent();event.down=down;event.time=time;event.action=action;event.x=x;event.y=y;return event;}
public void setSource(int source){this.source=source;}public long getDownTime(){return down;}public long getEventTime(){return time;}public int getAction(){return action;}public float getX(){return x;}public float getY(){return y;}public int getSource(){return source;}
}''',
'xiaomismartpen/plus/ColorScreenshot.java':'package xiaomismartpen.plus; final class ColorScreenshot {static int calls;static void run(android.content.Context context,ClassLoader loader){calls++;}}',
'com/miui/server/input/util/ShortCutActionsUtils.java':r'''package com.miui.server.input.util; import android.content.Context; import android.os.Bundle; public class ShortCutActionsUtils {
public static final ShortCutActionsUtils INSTANCE=new ShortCutActionsUtils();public static int calls;public static Object result=Boolean.TRUE;public static String function,reason;public static Bundle extras;public static boolean haptic;
public static ShortCutActionsUtils getInstance(Context context){return INSTANCE;}
public Object triggerFunction(String action,String source,Bundle bundle,boolean feedback){calls++;function=action;reason=source;extras=bundle;haptic=feedback;return result;}
}''',
'com/android/server/input/InputManagerInternal.java':'package com.android.server.input; public class InputManagerInternal {}',
'com/android/server/LocalServices.java':r'''package com.android.server; public class LocalServices {
public static Object service;public static int lookups;public static Object getService(Class<?> type){lookups++;return service;}
}''',
'xiaomismartpen/plus/ActionRunnerSenderTests.java':r'''package xiaomismartpen.plus;
import java.lang.reflect.*;import java.util.*;import android.view.*;import android.content.Context;import com.android.server.LocalServices;
final class ActionRunnerSenderTests {
static int checks;
static void eq(Object expected,Object actual,String message){checks++;if(!Objects.equals(expected,actual))throw new AssertionError(message+": expected="+expected+" actual="+actual);}
static void fails(Runnable task,String message){checks++;try{task.run();}catch(IllegalStateException expected){return;}throw new AssertionError(message);}
static void clearNative() throws Exception {Field f=ActionRunner.class.getDeclaredField("nativeInput");f.setAccessible(true);f.set(null,null);}
static void swipeTests(Context context) {
String[] actions={PenConfig.ACTION_SWIPE_LEFT,PenConfig.ACTION_SWIPE_RIGHT,PenConfig.ACTION_SWIPE_UP,PenConfig.ACTION_SWIPE_DOWN};
float[][] endpoints={{2160f,900f,720f,900f},{720f,900f,2160f,900f},{1440f,1296f,1440f,504f},{1440f,504f,1440f,1296f}};
for(int direction=0;direction<actions.length;direction++){
android.hardware.input.InputManager.sent.clear();android.os.Handler.now=20000;ActionRunner.run(context,actions[direction],0);
List<InputEvent> events=android.hardware.input.InputManager.sent;eq(10,events.size(),actions[direction]+" emits complete touch sequence");
MotionEvent first=(MotionEvent)events.get(0),last=(MotionEvent)events.get(events.size()-1);float[] p=endpoints[direction];
eq(MotionEvent.ACTION_DOWN,first.getAction(),"swipe starts with DOWN");eq(MotionEvent.ACTION_UP,last.getAction(),"swipe ends with UP");
eq(p[0],first.getX(),"swipe start x");eq(p[1],first.getY(),"swipe start y");eq(p[2],last.getX(),"swipe end x");eq(p[3],last.getY(),"swipe end y");
eq(160L,last.getEventTime()-first.getEventTime(),"swipe duration");eq(20000L,last.getEventTime(),"swipe ends at current uptime");
for(int index=0;index<events.size();index++){MotionEvent event=(MotionEvent)events.get(index);
eq(19840L,event.getDownTime(),"all touch events retain common DOWN time");eq(InputDevice.SOURCE_TOUCHSCREEN,event.getSource(),"swipe uses touch source");eq(1,event.recycleCount,"sender recycles touch event exactly once");
if(index>0){MotionEvent previous=(MotionEvent)events.get(index-1);eq(true,event.getEventTime()>=previous.getEventTime(),"touch timestamps are monotonic");
eq(true,p[2]>=p[0]?event.getX()>=previous.getX():event.getX()<=previous.getX(),"x moves toward requested side");
eq(true,p[3]>=p[1]?event.getY()>=previous.getY():event.getY()<=previous.getY(),"y moves toward requested side");}
if(index>0&&index<events.size()-1)eq(MotionEvent.ACTION_MOVE,event.getAction(),"intermediate event is MOVE");
}
}
}
static final class NativeProbe {final List<Object[]> calls=new ArrayList<>();Object result=0;boolean throwFailure;
public Object injectInputEvent(InputEvent event,boolean intoUid,int uid,int mode,int timeout,int flags){if(throwFailure)throw new IllegalStateException("native failure");calls.add(new Object[]{event,intoUid,uid,mode,timeout,flags});return result;}}
static final class ManagerProbe {final NativeProbe mNative=new NativeProbe();}
static final class LocalProbe {final ManagerProbe this$0=new ManagerProbe();}
public static void main(String[] args)throws Exception {
ActionRunner.setSystemClassLoader(ActionRunnerSenderTests.class.getClassLoader());clearNative();LocalServices.lookups=0;
LocalProbe local=new LocalProbe();LocalServices.service=local;Context context=new Context();
int[] codes={189,188,93,92};int[] expectedCodes={93,92,93,92};
for(int i=0;i<codes.length;i++)for(int action:new int[]{0,1}){
KeyEvent original=new KeyEvent(10000,10400,action,codes[i],action==0?2:0,0x12,new InputDevice("Xiaomi Smart Pen Keyboard",13),i==1?104:109,0x28,0x101,3);
ActionRunner.forwardPenEvent(context,original);Object[] call=local.this$0.mNative.calls.get(local.this$0.mNative.calls.size()-1);KeyEvent event=(KeyEvent)call[0];
eq(expectedCodes[i],event.getKeyCode(),"sender restores Xiaomi page key");eq(action,event.getAction(),"sender retains DOWN or UP");eq(original.getRepeatCount(),event.getRepeatCount(),"sender retains repeat count");
eq(10000L,event.getDownTime(),"sender retains down time");eq(10400L,event.getEventTime(),"sender retains event time");eq(13,event.getDeviceId(),"sender retains physical device id");
eq(original.getScanCode(),event.getScanCode(),"sender retains scan code");eq(0x12,event.getMetaState(),"sender retains meta state");eq(0x101,event.getSource(),"sender retains source");eq(3,event.getDisplayId(),"sender retains display id");
eq(0x2c,event.getFlags(),"sender preserves existing flags and adds keep-touch-mode");eq(0x28,original.getFlags(),"sender does not mutate original event");
eq(false,call[1],"native sender does not restrict target uid");eq(-1,call[2],"native sender target uid is unrestricted");eq(0,call[3],"native sender remains asynchronous");eq(0,call[4],"native sender does not wait");eq(0x44000000,call[5],"native sender uses filtered and pass-to-user flags without disabling repeats");
}
eq(1,LocalServices.lookups,"native service is resolved once and cached");KeyEvent key=new KeyEvent(0,189,0,false,new InputDevice("Xiaomi Smart Pen Keyboard",13));
android.os.Handler.now=10600;
for(int i=0;i<codes.length;i++){
KeyEvent held=new KeyEvent(10000,10000,0,codes[i],0,0x12,new InputDevice("Xiaomi Smart Pen Keyboard",13),i==1?104:109,0x8,0x101,3);
ActionRunner.cancelPenEvent(context,held);Object[] call=local.this$0.mNative.calls.get(local.this$0.mNative.calls.size()-1);KeyEvent release=(KeyEvent)call[0];
eq(1,release.getAction(),"native cancellation is an UP");eq(expectedCodes[i],release.getKeyCode(),"native cancellation uses mapped page key");eq(0,release.getRepeatCount(),"native cancellation clears repeat count");
eq(10000L,release.getDownTime(),"native cancellation retains original press time");eq(10600L,release.getEventTime(),"native cancellation releases at current uptime");
eq(13,release.getDeviceId(),"native cancellation keeps physical identity");eq(held.getScanCode(),release.getScanCode(),"native cancellation keeps scan code");eq(0x12,release.getMetaState(),"native cancellation keeps modifiers");eq(0x101,release.getSource(),"native cancellation keeps source");eq(3,release.getDisplayId(),"native cancellation keeps display");
eq(0x2c,release.getFlags(),"native cancellation adds cancelled and keep-touch-mode flags");eq(0x8,held.getFlags(),"native cancellation does not mutate held event");
eq(false,call[1],"native cancellation does not restrict target uid");eq(-1,call[2],"native cancellation uses unrestricted uid");eq(0,call[3],"native cancellation remains asynchronous");eq(0,call[4],"native cancellation does not wait");eq(0x04000000,call[5],"native cancellation omits pass-to-user to end repeat without ordinary UP in new focus");
}
for(Object rejected:new Object[]{1,2,Boolean.TRUE,null}){local.this$0.mNative.result=rejected;fails(()->ActionRunner.forwardPenEvent(context,key),"native rejection must be reported");}
for(Object rejected:new Object[]{1,2,Boolean.TRUE,null}){local.this$0.mNative.result=rejected;fails(()->ActionRunner.cancelPenEvent(context,key),"native cancellation rejection must be reported");}
local.this$0.mNative.result=0;local.this$0.mNative.throwFailure=true;fails(()->ActionRunner.forwardPenEvent(context,key),"native exception must be reported");local.this$0.mNative.throwFailure=false;
int calls=local.this$0.mNative.calls.size();fails(()->ActionRunner.forwardPenEvent(null,key),"missing system context must be reported");fails(()->ActionRunner.cancelPenEvent(null,key),"missing cancellation context must be reported");eq(calls,local.this$0.mNative.calls.size(),"missing context never sends input");
clearNative();LocalServices.service=null;fails(()->ActionRunner.forwardPenEvent(context,key),"missing local input service must be reported");swipeTests(context);
System.out.println("PASS "+checks+" production sender assertions (native endpoint substituted; Android dispatch validated separately)");
}
}''',
})
sender_stubs['android/hardware/input/InputManager.java']=sender_stubs['android/hardware/input/InputManager.java'].replace('public class InputManager {','public class InputManager {public static final java.util.List<android.view.InputEvent> sent=new java.util.ArrayList<>();public boolean injectInputEvent(android.view.InputEvent event,int mode){sent.add(event);return true;}')
sender_stubs['android/content/Context.java']=sender_stubs['android/content/Context.java'].replace('public static final String INPUT_SERVICE="input";', 'public static final String INPUT_SERVICE="input",AUDIO_SERVICE="audio"; public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}public void startActivity(Intent intent){}')
for name,content in sender_stubs.items():
    p=sender_sources/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8')
sender_files=[str(sender_sources/name) for name in sender_stubs]+[str(prod/'PenConfig.java'),str(prod/'PenKeyController.java'),str(prod/'ActionRunner.java'),str(prod/'ConfigStore.java'),str(prod/'RomProfile.java'),str(testroot/'ActionRoutingTests.java')]
subprocess.run([javac,'-encoding','UTF-8','-d',str(sender_classes),*sender_files],check=True)
subprocess.run([java,'-cp',str(sender_classes),'xiaomismartpen.plus.ActionRunnerSenderTests'],check=True)
for family in ('COLOROS','HYPEROS','UNKNOWN'):
    subprocess.run([java,'-cp',str(sender_classes),'xiaomismartpen.plus.ActionRoutingTests',family],check=True)

run_bridge_tests(build, prod, stubs, javac, java, testroot)
