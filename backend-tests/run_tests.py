"""Run production backend logic on a JVM with deterministic Android/Xposed test doubles.
No Android device, Gradle download, or system installation is required.
Usage: python backend-tests/run_tests.py --java-home <JDK directory>
"""
from pathlib import Path
import argparse, os, subprocess, shutil
parser=argparse.ArgumentParser(); parser.add_argument('--java-home'); args=parser.parse_args()
project=Path(__file__).resolve().parent.parent
build=project/'build'/'backend-tests'; sources=build/'stubs'; classes=build/'classes'
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
for name,content in stubs.items():
    p=sources/name; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(content,encoding='utf-8')
java_home=Path(args.java_home) if args.java_home else None
executable_suffix = '.exe' if os.name == 'nt' else ''
javac=str(java_home/'bin'/('javac'+executable_suffix)) if java_home else shutil.which('javac')
java=str(java_home/'bin'/('java'+executable_suffix)) if java_home else shutil.which('java')
if not java or not javac: raise SystemExit('A JDK is required; pass --java-home if it is not on PATH.')
prod=project/'app'/'src'/'main'/'java'/'xiaomismartpen'/'plus'
# Compile only this run's doubles; stale generated package paths from earlier runs are ignored.
files=[str(sources/name) for name in stubs]+[str(prod/'PenConfig.java'),str(prod/'PenKeyController.java'),str(prod/'ConfigStore.java'),str(project/'backend-tests'/'BackendTests.java'),str(project/'backend-tests'/'ConfigStoreTests.java')]
subprocess.run([javac,'-encoding','UTF-8','-d',str(classes),*files],check=True)
subprocess.run([java,'-cp',str(classes),'xiaomismartpen.plus.BackendTests'],check=True)
subprocess.run([java,'-cp',str(classes),'xiaomismartpen.plus.ConfigStoreTests'],check=True)
