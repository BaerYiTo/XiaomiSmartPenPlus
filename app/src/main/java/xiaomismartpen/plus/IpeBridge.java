package xiaomismartpen.plus;

import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** 将真实小米硬件状态送入当前 ROM 的 IPE 模型与实际页面 Binder 消费链。 */
final class IpeBridge {
    private static final String TAG="FocusPenLite";
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final Map<IBinder,Object> callbacks=new LinkedHashMap<>();
    private static volatile HardwareState state=new HardwareState(false,false,-1,"",0,false);
    private static volatile PenConfig config=PenConfig.defaults();
    private static Context context;
    private static ClassLoader loader;
    private static Handler worker;
    private static Object manager,originalTask,bridgeTask;
    private static Field taskField;
    private static int originalBattery;
    private static int bridgeBattery;
    private static HardwareState lastSent;
    private static boolean lastActive;
    private static int savedBattery, savedCharging;
    private static String savedMac;
    private static boolean installed,managed;

    static synchronized void install(ClassLoader cl,Context ctx) {
        if(installed) return;installed=true;context=ctx;loader=cl;
        HandlerThread thread=new HandlerThread("FocusPen-ipe");thread.start();worker=new Handler(thread.getLooper());
        try {
            Class<?> nativeManager=XposedHelpers.findClass("com.oplus.ipemanager.btadsorb.ble.k0",cl);
            XposedBridge.hookAllConstructors(nativeManager,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    main.post(() -> capture(hook.thisObject));
                }
            });
            for(String name:new String[]{"i0","h0","o","e0","d0"}) {
                Class<?> cls=XposedHelpers.findClassIfExists("com.oplus.ipemanager.btadsorb.ble."+name,cl);
                if(cls==null) continue;
                hookGetter(cls,"getConnectState",0);hookGetter(cls,"getPencilConnectState",0);
                hookGetter(cls,"getBatteryLevel",1);hookGetter(cls,"getConnectedPencilMac",2);
                hookGetter(cls,"getUpdateStatus",3);
                XposedBridge.hookAllMethods(cls,"setIUIPageCbBinder",new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        IBinder binder=hook.args.length>0?(IBinder)hook.args[0]:null;
                        if(binder==null) {main.post(callbacks::clear);return;}
                        main.post(() -> {
                            try {
                                Object callback=XposedHelpers.callStaticMethod(XposedHelpers.findClass("c3.d",loader),"b",binder);
                                callbacks.put(binder,callback);notifyCallback(callback);
                            } catch(Throwable failure) {Log.e(TAG,"FPL|ipe|register callback failed",failure);}
                        });
                    }
                });
                XposedBridge.hookAllMethods(cls,"clearIUIPageCbBinder",new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        if(hook.args.length>0) main.post(() -> callbacks.remove(hook.args[0]));
                    }
                });
            }
            worker.post(new Runnable(){@Override public void run(){try {reload();} finally {worker.postDelayed(this,5000);}}});
            ctx.getContentResolver().registerContentObserver(Settings.Global.getUriFor(HardwareState.KEY),false,
                new ContentObserver(worker){@Override public void onChange(boolean self){reload();}});
            ctx.getContentResolver().registerContentObserver(ConfigStore.SNAPSHOT_URI,false,
                new ContentObserver(worker){@Override public void onChange(boolean self){reload();}});
            Log.i(TAG,"FPL|ipe|installed "+android.os.Process.myPid());
        } catch(Throwable failure) {Log.e(TAG,"FPL|ipe|install failed",failure);}
    }
    private static void hookGetter(Class<?> cls,String method,int kind) {
        XposedBridge.hookAllMethods(cls,method,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                HardwareState current=state;
                if(!config.systemIntegration || !current.isFresh()) return;
                if(hook.args.length>0 && hook.args[0] instanceof String && !current.mac.equalsIgnoreCase((String)hook.args[0])) return;
                if(kind==0) hook.setResult(current.connected?2:0);
                else if(kind==1) hook.setResult(current.battery);
                else if(kind==2) hook.setResult(current.mac);
                else hook.setResult(0);
            }
        });
    }
    private static void reload() {
        try {
            Bundle snapshot=context.getContentResolver().call(ConfigStore.SNAPSHOT_URI,ConfigStore.METHOD_SNAPSHOT,null,null);
            if(snapshot!=null && snapshot.getString(ConfigStore.KEY_SNAPSHOT)!=null)
                config=PenConfig.parse(snapshot.getString(ConfigStore.KEY_SNAPSHOT));
        } catch(Throwable failure){Log.w(TAG,"FPL|ipe|configuration pending",failure);}
        try {
            state=HardwareState.read(context);
            main.post(IpeBridge::apply);
        } catch(Throwable failure){Log.w(TAG,"FPL|ipe|hardware pending",failure);}
    }
    private static void capture(Object nativeManager) {
        try {
            manager=nativeManager;
            for(Class<?> cls=manager.getClass();cls!=null;cls=cls.getSuperclass()) {
                for(Field field:cls.getDeclaredFields()) if(field.getType().getName().equals("com.oplus.ipemanager.btadsorb.ble.l0")) {
                    field.setAccessible(true);taskField=field;
                }
            }
            bridgeTask=null;managed=false;apply();
        } catch(Throwable failure){Log.e(TAG,"FPL|ipe|model capture failed",failure);}
    }
    private static void apply() {
        try {
            HardwareState current=state;
            boolean active=config.systemIntegration && current.isFresh();
            if(manager!=null && taskField!=null) {
                if(active) {
                    Object task=taskField.get(manager);
                    if(!managed || task!=bridgeTask) {
                        originalTask=task;
                        originalBattery=XposedHelpers.getIntField(manager,"L");
                        bridgeTask=XposedHelpers.newInstance(taskField.getType());
                        if(task!=null) for(Field field:taskField.getType().getDeclaredFields()) {
                            if(Modifier.isStatic(field.getModifiers())) continue;
                            field.setAccessible(true);field.set(bridgeTask,field.get(task));
                        }
                        taskField.set(manager,bridgeTask);
                    } else if(XposedHelpers.getIntField(manager,"L")!=bridgeBattery) {
                        originalBattery=XposedHelpers.getIntField(manager,"L");
                    }
                    XposedHelpers.setObjectField(bridgeTask,"a",current.mac);
                    XposedHelpers.setObjectField(bridgeTask,"c",Integer.valueOf(current.docked?1:0));
                    XposedHelpers.setIntField(bridgeTask,"f",current.connected?2:0);
                    XposedHelpers.setIntField(bridgeTask,"g",current.connected?2:0);
                    bridgeBattery=current.battery;
                    XposedHelpers.setIntField(manager,"L",bridgeBattery);
                    managed=true;
                } else if(managed) {
                    if(taskField.get(manager)==bridgeTask) taskField.set(manager,originalTask);
                    if(XposedHelpers.getIntField(manager,"L")==bridgeBattery)
                        XposedHelpers.setIntField(manager,"L",originalBattery);
                    managed=false;bridgeTask=null;
                }
            }
            boolean changed=lastActive!=active || lastSent==null || current.connected!=lastSent.connected
                    || current.docked!=lastSent.docked || current.battery!=lastSent.battery || !current.mac.equals(lastSent.mac);
            if(active) {
                if(!lastActive) {
                    Class<?> prefs=XposedHelpers.findClass("c2.b",loader);
                    savedBattery=(Integer)XposedHelpers.callStaticMethod(prefs,"c",context,-1,"pencil_sp_battery_level");
                    savedCharging=(Integer)XposedHelpers.callStaticMethod(prefs,"c",context,0,"pencil_sp_charging_state");
                    savedMac=(String)XposedHelpers.callStaticMethod(prefs,"e",context,"ipe_pencil_mac_addr","");
                }
                if(changed) {
                    putPreference("integer","pencil_sp_battery_level",current.battery);
                    putPreference("integer","pencil_sp_charging_state",current.docked?1:0);
                    putPreference("string","ipe_pencil_mac_addr",current.mac);
                }
            } else if(lastActive) {
                putPreference("integer","pencil_sp_battery_level",savedBattery);
                putPreference("integer","pencil_sp_charging_state",savedCharging);
                putPreference("string","ipe_pencil_mac_addr",savedMac);
            }
            Settings.Global.putLong(context.getContentResolver(),"focuspen_ipe_heartbeat",SystemClock.elapsedRealtime());
            callbacks.entrySet().removeIf(entry -> !entry.getKey().isBinderAlive());
            if(changed && (active || lastActive)) for(Object callback:callbacks.values()) notifyCallback(callback);
            lastSent=current;lastActive=active;
        } catch(Throwable failure){Log.e(TAG,"FPL|ipe|model update failed",failure);}
    }
    private static void putPreference(String type,String key,Object value) {
        ContentValues values=new ContentValues();
        if(value instanceof Integer)values.put(key,(Integer)value);
        else if(value instanceof Boolean)values.put(key,(Boolean)value);
        else values.put(key,(String)value);
        context.getContentResolver().insert(Uri.parse("content://com.oplus.ipemanager.provider/"+type+"/local_config/"+key+"/"+value),values);
    }
    private static void notifyCallback(Object callback) {
        if(callback==null) return;
        try {
            boolean active=config.systemIntegration && state.isFresh();
            int connection=0,battery=savedBattery;String mac="";
            if(active) {connection=state.connected?2:0;battery=state.battery;mac=state.mac;}
            else if(manager!=null && taskField!=null) {
                Object task=taskField.get(manager);
                if(task!=null) {connection=XposedHelpers.getIntField(task,"g");mac=(String)XposedHelpers.getObjectField(task,"a");}
                battery=XposedHelpers.getIntField(manager,"L");
            }
            XposedHelpers.callMethod(callback,"notifyConnectState",connection,mac==null?"":mac);
            XposedHelpers.callMethod(callback,"notifyBatteryLevel",battery);
            XposedHelpers.callMethod(callback,"notifyChargingState",active?state.docked:savedCharging==1||savedCharging==32);
        } catch(Throwable failure){Log.w(TAG,"FPL|ipe|callback failed",failure);}
    }
}
