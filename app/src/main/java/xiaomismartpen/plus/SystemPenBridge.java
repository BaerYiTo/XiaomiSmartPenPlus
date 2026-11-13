package xiaomismartpen.plus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** system_server 内的硬件状态消费者：系统电量通知、磁吸事件与唤醒。 */
final class SystemPenBridge {
    private static final String TAG="FocusPenLite";
    private static final Handler handler=new Handler(Looper.getMainLooper());
    private static volatile PenConfig config=PenConfig.defaults();
    private static Context context;
    private static HardwareState previous;
    private static boolean installed;
    private static boolean heartbeatInstalled;
    private static boolean managed;
    private static final Map<String,String> originalSettings=new LinkedHashMap<>();
    private static final Map<String,String> writtenSettings=new LinkedHashMap<>();
    static void configure(Context ctx,PenConfig next) {
        config=next;
        handler.post(() -> {
            try {
            context=ctx;
            if(!heartbeatInstalled) {
                heartbeatInstalled=true;
                handler.post(new Runnable() { @Override public void run() {
                    try {
                        Settings.Global.putLong(ctx.getContentResolver(),"focuspen_module_heartbeat",SystemClock.elapsedRealtime());
                        refresh();
                    } catch(Throwable failure) {Log.w(TAG,"FPL|system|heartbeat pending",failure);}
                    finally {handler.postDelayed(this,15000);}
                }});
            }
            if(RomProfile.detect()==RomProfile.Family.COLOROS && !installed) {
                ctx.getContentResolver().registerContentObserver(Settings.Global.getUriFor(HardwareState.KEY),false,
                    new ContentObserver(handler) { @Override public void onChange(boolean selfChange) { refresh(); } });
                installed=true;
                Log.i(TAG,"FPL|system|hardware observer installed");
            }
            refresh();
            } catch(Throwable failure) {Log.w(TAG,"FPL|system|observer pending",failure);}
        });
    }
    private static void refresh() {
        try {
            // HardwareState is shared by the module, but these settings belong exclusively to
            // the ColorOS/OPlus integration. HyperOS must keep the generic heartbeat without
            // writing ipe_pencil_* keys or publishing ColorOS notifications.
            if (RomProfile.detect() != RomProfile.Family.COLOROS) {
                previous = null;
                return;
            }
            HardwareState state=HardwareState.read(context);
            NotificationManager notifications=context.getSystemService(NotificationManager.class);
            if(notifications==null) return;
            boolean active=config.systemIntegration && state.isFresh();
            if(!active) {
                notifications.cancel(9270);notifications.cancel(9271);
                if(managed) for(Map.Entry<String,String> entry:originalSettings.entrySet()) {
                    String key=entry.getKey();
                    if(Objects.equals(Settings.Global.getString(context.getContentResolver(),key),writtenSettings.get(key)))
                        Settings.Global.putString(context.getContentResolver(),key,entry.getValue());
                }
                originalSettings.clear();writtenSettings.clear();managed=false;previous=null;return;
            }
            putSetting("ipe_pencil_connect_state",state.connected?"2":"0");
            putSetting("ipe_pencil_present",state.connected||state.docked?"1":"0");
            putSetting("ipe_pencil_battery_level",Integer.toString(state.battery));
            putSetting("ipe_pencil_charging_state",state.docked?"1":"0");
            putSetting("ipe_pencil_mac_addr",state.mac);
            putSetting("ipe_pencil_bt_device_name","Xiaomi Smart Pen");
            managed=true;
            if(state.connected || state.docked) {
                notifications.createNotificationChannel(new NotificationChannel("focuspen_battery","手写笔电量",NotificationManager.IMPORTANCE_LOW));
                notifications.notify(9270,notification("focuspen_battery",state,"小米手写笔",false)
                    .setOngoing(true).setOnlyAlertOnce(true).build());
            } else notifications.cancel(9270);
            if(previous!=null && previous.isFresh() && state.mac.equals(previous.mac) && state.docked!=previous.docked) {
                boolean dock=state.docked;
                if(dock?config.wakeOnDock:config.wakeOnLift) wake(dock?"dock":"lift");
                if(dock?config.dockHint:config.liftHint) {
                    notifications.createNotificationChannel(new NotificationChannel("focuspen_events","手写笔吸附与拿起",NotificationManager.IMPORTANCE_HIGH));
                    notifications.notify(9271,notification("focuspen_events",state,dock?"手写笔已吸附":"手写笔已拿起",true)
                        .setTimeoutAfter(5000).setAutoCancel(true).build());
                }
                Log.i(TAG,"FPL|hall|"+(dock?"DOCK":"LIFT")+" battery="+state.battery);
            }
            previous=state;
        } catch(Throwable failure) {Log.e(TAG,"FPL|system|refresh failed",failure);}
    }
    private static void putSetting(String key,String value) {
        if(!originalSettings.containsKey(key)) originalSettings.put(key,Settings.Global.getString(context.getContentResolver(),key));
        if(!Objects.equals(Settings.Global.getString(context.getContentResolver(),key),value))
            Settings.Global.putString(context.getContentResolver(),key,value);
        writtenSettings.put(key,value);
    }
    private static Notification.Builder notification(String channel,HardwareState state,String title,boolean event) {
        Intent page=SystemSettings.colorOsIntent();
        PendingIntent pending=PendingIntent.getActivity(context,9270,page,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String battery=state.battery<0?"电量暂不可读":("电量 "+state.battery+"%");
        return new Notification.Builder(context,channel).setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title).setContentText(battery+" · "+(state.docked?"已吸附，充电中":state.connected?"已连接":"未连接"))
            .setShowWhen(event).setVisibility(Notification.VISIBILITY_PUBLIC).setContentIntent(pending)
            .setCategory(Notification.CATEGORY_STATUS);
    }
    private static void wake(String reason) {
        try {
            PowerManager power=context.getSystemService(PowerManager.class);
            if(power!=null && !power.isInteractive()) {
                PowerManager.class.getMethod("wakeUp",long.class,int.class,String.class)
                    .invoke(power,SystemClock.uptimeMillis(),2,"FocusPen:"+reason);
                Log.i(TAG,"FPL|wake|"+reason);
            }
        } catch(Throwable failure) {Log.e(TAG,"FPL|wake|failed",failure);}
    }
}
