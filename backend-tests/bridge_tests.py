"""Focused JVM regression for the production SystemPenBridge ROM boundary."""
from pathlib import Path
import subprocess


def run_bridge_tests(build, prod, stubs, javac, java, testroot):
    """Accept either the runner's stub dict or its generated stub directory."""
    build, prod, testroot = map(Path, (build, prod, testroot))
    sources, classes = build / 'bridge-stubs', build / 'bridge-classes'
    sources.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    names = ('android/os/Looper.java', 'android/os/Handler.java',
             'android/os/SystemClock.java', 'android/os/Build.java',
             'android/os/SystemProperties.java', 'android/database/ContentObserver.java')
    doubles = {name: stubs[name] if isinstance(stubs, dict)
               else (Path(stubs) / name).read_text(encoding='utf-8') for name in names}
    doubles.update({
        'xiaomismartpen/plus/PenKeyController.java': 'package xiaomismartpen.plus; final class PenKeyController { static final int KEY_BUTTON_1=93,KEY_BUTTON_2=92,KEY_COLOROS_LOWER=189,KEY_COLOROS_UPPER=188; }',
        'android/content/Context.java': r'''package android.content;
import android.app.*; import android.os.PowerManager;
public class Context {
  private final ContentResolver resolver=new ContentResolver();
  public ContentResolver getContentResolver(){return resolver;}
  public <T>T getSystemService(Class<T> type){
    if(type==NotificationManager.class)return type.cast(NotificationManager.instance);
    if(type==PowerManager.class)return type.cast(new PowerManager());
    return null;
  }
}''',
        'android/content/ContentResolver.java': r'''package android.content;
import android.database.ContentObserver;
public class ContentResolver {
  public static int registrations;
  public static void reset(){registrations=0;}
  public void registerContentObserver(Object uri,boolean descendants,ContentObserver observer){registrations++;}
}''',
        'android/provider/Settings.java': r'''package android.provider;
import java.util.*; import android.content.ContentResolver;
public final class Settings {
  public static final class Global {
    public static final Map<String,String> values=new LinkedHashMap<>();
    public static final List<String> writtenKeys=new ArrayList<>();
    public static void reset(){values.clear();writtenKeys.clear();}
    public static String getString(ContentResolver resolver,String key){return values.get(key);}
    public static boolean putString(ContentResolver resolver,String key,String value){values.put(key,value);writtenKeys.add(key);return true;}
    public static boolean putLong(ContentResolver resolver,String key,long value){return putString(resolver,key,Long.toString(value));}
    public static Object getUriFor(String key){return "content://settings/global/"+key;}
  }
}''',
        'android/app/Notification.java': r'''package android.app;
public class Notification {
  public static final int VISIBILITY_PUBLIC=1;
  public static final String CATEGORY_STATUS="status";
  public static class Builder {
    public Builder(android.content.Context context,String channel){}
    public Builder setSmallIcon(int icon){return this;}
    public Builder setContentTitle(String value){return this;}
    public Builder setContentText(String value){return this;}
    public Builder setShowWhen(boolean value){return this;}
    public Builder setVisibility(int value){return this;}
    public Builder setContentIntent(PendingIntent value){return this;}
    public Builder setCategory(String value){return this;}
    public Builder setOngoing(boolean value){return this;}
    public Builder setOnlyAlertOnce(boolean value){return this;}
    public Builder setTimeoutAfter(long value){return this;}
    public Builder setAutoCancel(boolean value){return this;}
    public Notification build(){return new Notification();}
  }
}''',
        'android/app/NotificationChannel.java': r'''package android.app;
public class NotificationChannel {
  public final String id;
  public NotificationChannel(String id,String name,int importance){this.id=id;}
}''',
        'android/app/NotificationManager.java': r'''package android.app;
import java.util.*;
public class NotificationManager {
  public static final int IMPORTANCE_LOW=2,IMPORTANCE_HIGH=4;
  public static final NotificationManager instance=new NotificationManager();
  public static final List<String> channels=new ArrayList<>();
  public static final List<Integer> notifications=new ArrayList<>();
  public static void reset(){channels.clear();notifications.clear();}
  public void createNotificationChannel(NotificationChannel channel){channels.add(channel.id);}
  public void notify(int id,Notification notification){notifications.add(id);}
  public void cancel(int id){}
}''',
        'android/app/PendingIntent.java': r'''package android.app;
public class PendingIntent {
  public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;
  public static PendingIntent getActivity(android.content.Context context,int request,android.content.Intent intent,int flags){return new PendingIntent();}
}''',
        'android/content/Intent.java': 'package android.content; public class Intent { public Intent(String action){} }',
        'android/os/PowerManager.java': 'package android.os; public class PowerManager { public boolean isInteractive(){return true;} public void wakeUp(long when,int reason,String details){} }',
        'android/R.java': 'package android; public final class R { public static final class drawable { public static final int ic_menu_edit=1; } }',
        'android/util/Log.java': r'''package android.util;
public class Log {
  public static int i(String tag,String message){return 0;}
  public static int w(String tag,String message,Throwable failure){throw new AssertionError(message,failure);}
  public static int e(String tag,String message,Throwable failure){throw new AssertionError(message,failure);}
}''',
        'xiaomismartpen/plus/HardwareState.java': r'''package xiaomismartpen.plus;
import android.content.Context;
final class HardwareState {
  static final String KEY="focuspen_hardware";
  static int reads;
  final boolean connected=true,docked=false;
  final int battery=88;
  final String mac="11:22:33:44:55:66";
  boolean isFresh(){return true;}
  static HardwareState read(Context context){reads++;return new HardwareState();}
}''',
        'xiaomismartpen/plus/SystemSettings.java': r'''package xiaomismartpen.plus;
import android.content.Intent;
final class SystemSettings { static Intent colorOsIntent(){return new Intent("coloros.pen");} }
''',
    })
    for name, content in doubles.items():
        path = sources / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
    files = [str(sources / name) for name in doubles]
    files += [str(prod / name) for name in ('PenConfig.java', 'RomProfile.java', 'SystemPenBridge.java')]
    files.append(str(testroot / 'SystemPenBridgeTests.java'))
    subprocess.run([str(javac), '-encoding', 'UTF-8', '-d', str(classes), *files], check=True)
    # RomProfile and the bridge cache process state, so each ROM gets a fresh JVM.
    for case in ('color', 'hyper', 'unknown'):
        subprocess.run([str(java), '-cp', str(classes), 'xiaomismartpen.plus.SystemPenBridgeTests', case], check=True)
