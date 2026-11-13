package xiaomismartpen.plus;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.InputEvent;
import android.content.ContentResolver;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Exercises production config parsing and controller event handling with a deterministic clock. */
public final class BackendTests {
    static int checks;
    static void eq(Object expected, Object actual, String message) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
    }
    static void yes(boolean actual, String message) { eq(true, actual, message); }
    static PenConfig active(String action) {
        PenConfig p = PenConfig.defaults(); p.setTakeover(PenConfig.B1, true);
        p.setAction(PenConfig.B1, PenConfig.SHORT, action);
        p.setAction(PenConfig.B1, PenConfig.LONG, PenConfig.ACTION_BACK);
        p.setAction(PenConfig.B1, PenConfig.DOUBLE, PenConfig.ACTION_HOME);
        return p;
    }
    static void configTests() {
        PenConfig p = PenConfig.defaults();
        yes(!p.owns(PenConfig.B1) && !p.owns(PenConfig.B2), "default config preserves native buttons");
        p = active(PenConfig.ACTION_VOLUME_UP);
        p.longPressMs = 700; p.doubleWindowMs = 250;
        PenConfig a = p.ensureAppConfig("com.example.reader");
        eq(PenConfig.ACTION_VOLUME_UP, a.action(PenConfig.B1, PenConfig.SHORT), "new app copies global actions");
        eq(700L, a.longPressMs, "new app copies timing");
        a.setAction(PenConfig.B1, PenConfig.SHORT, PenConfig.ACTION_PAGE_DOWN);
        a.longPressMs = 900;
        PenConfig b = p.ensureAppConfig("com.example.video"); b.setAction(PenConfig.B1, PenConfig.SHORT, PenConfig.ACTION_SWIPE_UP);
        p.setAction(PenConfig.B1, PenConfig.SHORT, PenConfig.ACTION_VOLUME_DOWN);
        eq(PenConfig.ACTION_PAGE_DOWN, a.action(PenConfig.B1, PenConfig.SHORT), "global edits do not overwrite app config");
        eq(PenConfig.ACTION_VOLUME_DOWN, p.action(PenConfig.B1, PenConfig.SHORT), "app edits do not overwrite global");
        PenConfig round = PenConfig.parse(p.serialize());
        eq(p.serialize(), round.serialize(), "entire snapshot round trip");
        eq(900L, round.appConfig("com.example.reader").longPressMs, "per-app timing persists");
        eq(PenConfig.ACTION_SWIPE_UP, round.appConfig("com.example.video").action(PenConfig.B1, PenConfig.SHORT), "second app persists independently");
        eq(p, p.resolveForPackage(null), "unknown focus falls back to global");
        eq(p, p.resolveForPackage("com.unconfigured"), "unconfigured application uses global");
        p.removeAppConfig("com.example.reader");
        eq(p, p.resolveForPackage("com.example.reader"), "deleting an override restores global behavior");
        PenConfig clone = p.copy(); clone.ensureAppConfig("com.example.video").setAction(PenConfig.B1, PenConfig.SHORT, PenConfig.ACTION_NONE);
        eq(PenConfig.ACTION_SWIPE_UP, p.appConfig("com.example.video").action(PenConfig.B1, PenConfig.SHORT), "copy has no shared app mutation");
        p.setAppConfig("com.example.reader", clone); clone.setAction(PenConfig.B1, PenConfig.SHORT, PenConfig.ACTION_NONE);
        yes(!p.appConfig("com.example.reader").action(PenConfig.B1, PenConfig.SHORT).equals(PenConfig.ACTION_NONE), "setAppConfig copies supplied settings");
        eq(0, p.appConfig("com.example.reader").appPackages().size(), "app config contains no nested app profiles");
        PenConfig migrated = PenConfig.parse("apps.native=com.example.notes, android\nb1.takeover=true\nb1.short=swipe_down\ntiming.long=650\n");
        eq(2, migrated.appPackages().size(), "old exclusion entries migrate to ordinary app profiles");
        yes(migrated.owns(PenConfig.B1), "migration leaves global mapping enabled");
        yes(!migrated.appConfig("com.example.notes").owns(PenConfig.B1), "migrated app preserves native behavior");
        eq(650L, migrated.appConfig("com.example.notes").longPressMs, "migration captures final global settings regardless of line order");
        yes(!migrated.serialize().contains("apps.native"), "old exclusion key removed from persisted format");
        PenConfig existing = PenConfig.parse("b1.takeover=true\nb2.takeover=true\nb1.short=swipe_down\nb1.long=play_pause\nb1.double=volume_up\nb2.short=swipe_up\nb2.long=play_pause\nb2.double=volume_down\napps.native=com.onyx.galaxy.note\ntiming.long=850\ntiming.double=300\n");
        eq(PenConfig.ACTION_SWIPE_DOWN, existing.action(PenConfig.B1, PenConfig.SHORT), "existing first-button action preserved during upgrade");
        eq(PenConfig.ACTION_VOLUME_DOWN, existing.action(PenConfig.B2, PenConfig.DOUBLE), "existing second-button action preserved during upgrade");
        eq(850L, existing.longPressMs, "existing hold threshold preserved during upgrade");
        PenConfig notes = existing.appConfig("com.onyx.galaxy.note");
        yes(!notes.takeover(PenConfig.B1) && !notes.takeover(PenConfig.B2), "existing excluded note app migrates with both buttons native");
        eq(PenConfig.ACTION_SWIPE_UP, notes.action(PenConfig.B2, PenConfig.SHORT), "migrated profile retains mappings for later editing");
        eq(existing.serialize(), PenConfig.parse(existing.serialize()).serialize(), "upgraded real-shaped snapshot remains stable on subsequent reads");
        PenConfig explicit = active(PenConfig.ACTION_SWIPE_UP); explicit.setAppConfig("com.example.notes", active(PenConfig.ACTION_PAGE_UP));
        PenConfig merged = PenConfig.parse(explicit.serialize() + "apps.native=com.example.notes\n");
        eq(PenConfig.ACTION_PAGE_UP, merged.appConfig("com.example.notes").action(PenConfig.B1, PenConfig.SHORT), "explicit app config wins over legacy migration");
        PenConfig invalid = PenConfig.parse("b1.short=unknown_action\nb2.long=launch:evil\napp.bad!pkg=AAAA\napp.com.example.bad=%%%%\ntiming.long=99999\ntiming.double=bad\n");
        eq(PenConfig.ACTION_NONE, invalid.action(PenConfig.B1, PenConfig.SHORT), "unknown actions are neutralized");
        eq(1500L, invalid.longPressMs, "timings clamp to allowed bounds");
        eq(300L, invalid.doubleWindowMs, "invalid timing uses default");
        eq(0, invalid.appPackages().size(), "malformed profile entries ignored");
        String nested = Base64.getEncoder().encodeToString(("app.com.nested=" + Base64.getEncoder().encodeToString("b1.takeover=true\n".getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8));
        eq(0, PenConfig.parse("app.com.example=" + nested).appConfig("com.example").appPackages().size(), "nested snapshot payload cannot recurse");
        List<String> returned = p.appPackages(); returned.clear(); yes(!p.appPackages().isEmpty(), "appPackages returns defensive list");
    }
    static Field field(String name) throws Exception { Field f=PenKeyController.class.getDeclaredField(name); f.setAccessible(true); return f; }
    static void focus(String pkg) throws Exception { Method m=PenKeyController.class.getDeclaredMethod("updateFocus", String.class); m.setAccessible(true); m.invoke(null,pkg); }
    static void begin(PenConfig p, String pkg) throws Exception {
        ((Map<?,?>)field("STATES").get(null)).clear(); Handler.reset(); ActionRunner.actions.clear(); ActionRunner.forwarded.clear();
        ActionRunner.failForward=false;ActionRunner.forwardAttempts=0;
        ActionRunner.cancelled.clear();ActionRunner.failCancel=false;ActionRunner.cancelAttempts=0;
        filteredKeys().clear();
        ContentResolver.reset();
        android.provider.Settings.Global.reset(); field("lastSnapshot").set(null,null);
        field("handler").set(null,new Handler(Looper.getMainLooper())); field("configHandler").set(null,null);
        field("config").set(null,p.copy()); field("configLoadedAt").setLong(null,Handler.now);
        field("cachedContext").set(null,new android.content.Context());
        field("focusedPackage").set(null,null); field("focusEpoch").setLong(null,0L); focus(pkg);
    }
    static boolean event(int action, int repeat, boolean cancelled, InputDevice device) throws Exception {
        return eventCode(action,KeyEvent.KEYCODE_PAGE_DOWN,repeat,cancelled,device);
    }
    static boolean eventCode(int action, int code, int repeat, boolean cancelled, InputDevice device) throws Exception {
        return Objects.equals(0,dispatch(new KeyEvent(action,code,repeat,cancelled,device),0));
    }
    static Object dispatch(KeyEvent event, int policyFlags) throws Exception {
        Method m=PenKeyController.class.getDeclaredMethod("handle", XC_MethodHook.MethodHookParam.class, KeyEvent.class); m.setAccessible(true);
        XC_MethodHook.MethodHookParam param=new XC_MethodHook.MethodHookParam();
        param.args=new Object[]{event,policyFlags};
        m.invoke(null,param,event);
        return param.result;
    }
    static Set<?> filteredKeys() throws Exception {return (Set<?>)field("FILTERED_KEYS").get(null);}
    static XC_MethodHook.MethodHookParam hook(String name, boolean after, Object target, Object... args) throws Exception {
        XC_MethodHook callback=XposedHelpers.hooks.get(name);
        if(callback==null)throw new IllegalStateException("Missing hook: "+name);
        XC_MethodHook.MethodHookParam param=new XC_MethodHook.MethodHookParam();param.args=args;param.thisObject=target;
        if(name.equals("start"))param.method=ManagerProbe.class.getDeclaredMethod("start");
        else if(name.equals("setInputFilter"))param.method=ManagerProbe.class.getDeclaredMethod("setInputFilter",Object.class);
        Method method=XC_MethodHook.class.getDeclaredMethod(after?"afterHookedMethod":"beforeHookedMethod",XC_MethodHook.MethodHookParam.class);
        method.setAccessible(true);method.invoke(callback,param);return param;
    }
    static Object queue(KeyEvent event, int flags) throws Exception {return hook("interceptKeyBeforeQueueing",false,null,event,flags).result;}
    static Object filter(InputEvent event) throws Exception {return hook("filterInputEvent",false,null,event,0).result;}
    static boolean down() throws Exception { return event(KeyEvent.ACTION_DOWN,0,false,new InputDevice("Xiaomi Smart Pen Keyboard")); }
    static boolean up() throws Exception { return event(KeyEvent.ACTION_UP,0,false,new InputDevice("Xiaomi Smart Pen Keyboard")); }
    static void click() throws Exception { yes(down(),"configured DOWN intercepted"); Handler.advance(20); yes(up(),"paired UP intercepted"); }
    static void actions(String... expected) { eq(Arrays.asList(expected),ActionRunner.actions,"emitted actions"); }
    static void watch() throws Exception {
        Method m=PenKeyController.class.getDeclaredMethod("watchConfig"); m.setAccessible(true); m.invoke(null);
    }
    static PenConfig loaded() throws Exception {return (PenConfig)field("config").get(null);}
    static void startWatcher(PenConfig initial, String pkg) throws Exception {
        begin(initial,pkg);
        field("configHandler").set(null,new Handler(Looper.getMainLooper()));
        field("observerRegistered").setBoolean(null,false);field("configObserver").set(null,null);
    }
    static void observerTests() throws Exception {
        PenConfig p=active(PenConfig.ACTION_VOLUME_UP);
        p.setAppConfig("com.example.a",active(PenConfig.ACTION_VOLUME_DOWN));
        startWatcher(p,"com.example.a");
        ContentResolver.failRegistration=true;ContentResolver.failSnapshot=true;
        watch();
        eq(1,ContentResolver.registrationAttempts,"boot attempts observer registration even while provider unavailable");
        eq(0,ContentResolver.registrations,"failed observer registration is not marked successful");
        eq(1,Handler.pendingCount(),"locked-user boot creates only one retry chain");
        PenConfig afterUnlock=p.copy();afterUnlock.removeAppConfig("com.example.a");
        ContentResolver.snapshot=afterUnlock.serialize();ContentResolver.failSnapshot=false;ContentResolver.failRegistration=false;
        Handler.advance(2000);
        eq(0,loaded().appPackages().size(),"first successful background reload picks up removed app override");
        eq(1,ContentResolver.registrations,"observer re-registers once provider becomes available");
        eq(2,ContentResolver.registrationAttempts,"failed boot registration is retried");
        yes(field("observerRegistered").getBoolean(null),"successful observer registration tracked");
        eq(1,Handler.pendingCount(),"successful reload leaves one periodic refresh chain");
        PenConfig changed=active(PenConfig.ACTION_HOME);ContentResolver.snapshot=changed.serialize();
        ContentResolver.fireChange();Handler.advance(0);
        eq(PenConfig.ACTION_HOME,loaded().action(PenConfig.B1,PenConfig.SHORT),"registered content observer updates snapshot immediately");
        eq(1,Handler.pendingCount(),"observer notifications do not duplicate background polling chains");
        Handler.advance(10000);
        eq(1,ContentResolver.registrations,"normal periodic refresh never duplicates successful registration");
        eq(2,ContentResolver.registrationAttempts,"registered observer is not retried on every tick");
        eq(1,Handler.pendingCount(),"multiple successful ticks retain one future task");

        startWatcher(p,"com.example.a");ContentResolver.snapshot=p.serialize();ContentResolver.failRegistration=true;
        watch();
        eq(1,loaded().appPackages().size(),"provider can load while observer registration fails");
        ContentResolver.snapshot=afterUnlock.serialize();
        Handler.advance(2000);
        eq(0,loaded().appPackages().size(),"failed-observer fallback refreshes deletion without any pen input");
        actions();
        eq(0,ContentResolver.registrations,"permanent observer failure remains unregistered");
        yes(ContentResolver.registrationAttempts>=2,"permanent failure keeps retrying on background handler");
        eq(1,Handler.pendingCount(),"observer failure does not spawn multiple retry tasks");
        int reads=ContentResolver.snapshotReads;
        click();Handler.advance(300);actions(PenConfig.ACTION_VOLUME_UP);
        eq(reads,ContentResolver.snapshotReads,"first key after background deletion uses new global config without input-thread IPC");
        Handler.advance(6000);
        eq(1,Handler.pendingCount(),"extended observer failure retains one retry chain");

        startWatcher(p,"com.example.a");ContentResolver.snapshot=p.serialize();watch();
        ContentResolver.snapshot=afterUnlock.serialize();
        Handler.advance(4999);
        eq(1,loaded().appPackages().size(),"normal fallback waits for its scheduled interval");
        Handler.advance(1);
        eq(0,loaded().appPackages().size(),"normal periodic fallback refreshes even if a registered notification is lost");
        eq(1,ContentResolver.registrations,"lost notifications do not cause duplicate registrations");

        startWatcher(p,"com.example.a");ContentResolver.failSnapshot=true;ContentResolver.snapshot=afterUnlock.serialize();watch();
        eq(1,ContentResolver.registrations,"observer may register before provider can answer");
        ContentResolver.failSnapshot=false;Handler.advance(2000);
        eq(0,loaded().appPackages().size(),"provider-only boot failure retries independently until first successful read");
        eq(1,ContentResolver.registrations,"provider retry does not duplicate a successful observer");
        eq(1,Handler.pendingCount(),"provider-only recovery transitions to one normal background refresh chain");
    }
    static void controllerTests() throws Exception {
        PenConfig p=active(PenConfig.ACTION_VOLUME_UP);
        p.setAppConfig("com.example.a",active(PenConfig.ACTION_VOLUME_DOWN));
        p.setAppConfig("com.example.b",active(PenConfig.ACTION_PAGE_DOWN));
        p.setAppConfig("com.example.native",PenConfig.defaults());
        begin(p,"com.unconfigured"); click(); Handler.advance(300); actions(PenConfig.ACTION_VOLUME_UP);
        begin(p,"com.example.a"); click(); Handler.advance(300); actions(PenConfig.ACTION_VOLUME_DOWN);
        begin(p,"com.example.native"); yes(!down(),"disabled app DOWN stays native"); yes(!up(),"disabled app UP stays native"); Handler.advance(1000); actions();
        begin(p,"com.example.a"); yes(down(),"app A starts hold"); focus("com.example.native"); Handler.advance(600); yes(up(),"focus change still swallows original owned UP"); actions();
        begin(p,"com.example.a"); click(); focus("com.example.b"); Handler.advance(100); click(); Handler.advance(300); actions(PenConfig.ACTION_PAGE_DOWN);
        begin(p,"com.example.a"); click(); focus("com.example.b"); focus("com.example.a"); Handler.advance(300); actions();
        begin(p,"com.example.a"); yes(down(),"hold starts with app snapshot"); PenConfig changed=p.copy(); changed.appConfig("com.example.a").setAction(PenConfig.B1,PenConfig.LONG,PenConfig.ACTION_HOME); field("config").set(null,changed); Handler.advance(400); actions(PenConfig.ACTION_BACK); yes(up(),"hold UP swallowed after config update"); Handler.advance(400); actions(PenConfig.ACTION_BACK);
        begin(p,"com.example.a"); click(); PenConfig changedDouble=p.copy(); changedDouble.appConfig("com.example.a").setAction(PenConfig.B1,PenConfig.DOUBLE,PenConfig.ACTION_RECENTS); field("config").set(null,changedDouble); Handler.advance(80); click(); Handler.advance(0); actions(PenConfig.ACTION_HOME);
        begin(p,"com.example.a"); click(); Handler.advance(80); click(); focus("com.example.b"); Handler.advance(0); actions();
        begin(p,"com.example.a"); yes(down(),"cancel starts hold"); yes(event(KeyEvent.ACTION_UP,0,true,new InputDevice("Xiaomi Smart Pen Keyboard")),"cancelled UP swallowed"); Handler.advance(1000); actions();
        begin(p,"com.example.a"); yes(!event(KeyEvent.ACTION_DOWN,0,false,null),"injected key must not recurse"); yes(!event(KeyEvent.ACTION_DOWN,0,false,new InputDevice("Keyboard")),"other devices untouched"); actions();
        begin(p,"com.example.a"); yes(down(),"repeat scenario DOWN owned"); focus("com.example.native"); yes(event(KeyEvent.ACTION_DOWN,1,false,new InputDevice("Xiaomi Smart Pen Keyboard")),"repeats retain original ownership"); yes(up(),"repeat scenario original UP owned"); Handler.advance(1000); actions();
        begin(p,"com.example.native"); yes(!down(),"native DOWN passes"); field("config").set(null,active(PenConfig.ACTION_HOME)); yes(!up(),"native UP passes even if snapshot enables takeover"); Handler.advance(1000); actions();
        begin(p,"com.example.a"); PenConfig t=p.copy(); t.appConfig("com.example.a").doubleWindowMs=650; field("config").set(null,t); click(); Handler.advance(300); actions(); Handler.advance(350); actions(PenConfig.ACTION_VOLUME_DOWN);
        begin(p,"com.example.a"); click(); focus(null); Handler.advance(300); actions(); click(); Handler.advance(300); actions(PenConfig.ACTION_VOLUME_UP);
    }
    static void portMappingTests() throws Exception {
        PenConfig p=active(PenConfig.ACTION_PASSTHROUGH);
        p.setTakeover(PenConfig.B2,true);
        p.setAction(PenConfig.B2,PenConfig.SHORT,PenConfig.ACTION_HOME);
        InputDevice pen=new InputDevice("Xiaomi Smart Pen Keyboard");
        for(int code:new int[]{KeyEvent.KEYCODE_PAGE_DOWN,KeyEvent.KEYCODE_BUTTON_2}) {
            begin(p,"com.unconfigured");
            yes(eventCode(KeyEvent.ACTION_DOWN,code,0,false,pen),"lower mapped DOWN owned");
            yes(eventCode(KeyEvent.ACTION_UP,code,0,false,pen),"lower mapped UP owned");
            Handler.advance(300);actions();
            eq(2,ActionRunner.forwarded.size(),"mixed short passthrough requests both original events");
            eq(code,ActionRunner.forwarded.get(0).getKeyCode(),"controller gives runner original lower keycode");
            eq(KeyEvent.ACTION_DOWN,ActionRunner.forwarded.get(0).getAction(),"short passthrough starts with DOWN");
            eq(KeyEvent.ACTION_UP,ActionRunner.forwarded.get(1).getAction(),"short passthrough ends with UP");
        }
        for(int code:new int[]{KeyEvent.KEYCODE_PAGE_UP,KeyEvent.KEYCODE_BUTTON_1}) {
            begin(p,"com.unconfigured");
            yes(eventCode(KeyEvent.ACTION_DOWN,code,0,false,pen),"upper mapped DOWN owned");
            yes(eventCode(KeyEvent.ACTION_UP,code,0,false,pen),"upper mapped UP owned");
            Handler.advance(300);actions(PenConfig.ACTION_HOME);
        }
        begin(p,"com.unconfigured");
        yes(!eventCode(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_1,0,false,new InputDevice("Gamepad")),"gamepad numbered button passes");
        yes(!eventCode(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_2,0,false,null),"injected mapped key passes without recursion");
        begin(PenConfig.defaults(),"com.unconfigured");
        yes(!eventCode(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_2,0,false,pen),"disabled mapped DOWN stays native");
        yes(!eventCode(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_2,0,false,pen),"disabled mapped UP stays native");
    }
    static PenConfig passthroughProfile() {
        PenConfig p=PenConfig.defaults();
        for(String button:new String[]{PenConfig.B1,PenConfig.B2}) {
            p.setTakeover(button,true);
            for(String slot:PenConfig.SLOTS)p.setAction(button,slot,PenConfig.ACTION_PASSTHROUGH);
        }
        return p;
    }
    static KeyEvent richEvent(long downTime,long eventTime,int action,int code,int repeat,int flags) {
        return new KeyEvent(downTime,eventTime,action,code,repeat,2,
                new InputDevice("Xiaomi Smart Pen Keyboard",57),code==KeyEvent.KEYCODE_BUTTON_1?104:109,
                flags,0x101,3);
    }
    static String eventFields(KeyEvent event) {
        return event.getAction()+":"+event.getKeyCode()+":"+event.getRepeatCount()+":"+event.getDownTime()+":"+
                event.getEventTime()+":"+event.getMetaState()+":"+event.getDeviceId()+":"+event.getScanCode()+":"+
                event.getFlags()+":"+event.getSource()+":"+event.getDisplayId();
    }
    static void forwarded(KeyEvent... expected) {
        eq(expected.length,ActionRunner.forwarded.size(),"forwarded event count");
        for(int i=0;i<expected.length;i++)eq(eventFields(expected[i]),eventFields(ActionRunner.forwarded.get(i)),
                "controller preserves original event fields at index "+i);
    }
    static void cancelled(KeyEvent... expected) {
        eq(expected.length,ActionRunner.cancelled.size(),"native cancellation request count");
        for(int i=0;i<expected.length;i++)eq(eventFields(expected[i]),eventFields(ActionRunner.cancelled.get(i)),
                "native cancellation identifies the original press at index "+i);
    }
    static void passthroughTests() throws Exception {
        PenConfig all=passthroughProfile();
        yes(all.passthroughOnly(PenConfig.B1)&&all.passthroughOnly(PenConfig.B2),"both all-pass buttons select streaming path");
        all.setTakeover(PenConfig.B1,false);yes(!all.passthroughOnly(PenConfig.B1),"disabled all-pass button stays native");
        all.setTakeover(PenConfig.B1,true);all.setAction(PenConfig.B1,PenConfig.LONG,PenConfig.ACTION_NONE);
        yes(!all.passthroughOnly(PenConfig.B1),"mixed none slot requires gesture recognition");
        all=passthroughProfile();
        for(int code:new int[]{KeyEvent.KEYCODE_PAGE_DOWN,KeyEvent.KEYCODE_PAGE_UP,KeyEvent.KEYCODE_BUTTON_2,KeyEvent.KEYCODE_BUTTON_1}) {
            begin(all,"com.example.notes");
            KeyEvent d=richEvent(10000,10000,KeyEvent.ACTION_DOWN,code,0,0x80);
            KeyEvent u=richEvent(10000,10040,KeyEvent.ACTION_UP,code,0,0x80);
            eq(0,dispatch(d,0),"all-pass DOWN is taken for compatible forwarding");forwarded(d);
            eq(0,dispatch(u,0),"all-pass UP is taken for compatible forwarding");forwarded(d,u);
            eq(0,Handler.pendingCount(),"all-pass click has no delayed gesture task");actions();
            Handler.advance(1000);forwarded(d,u);actions();
        }

        begin(all,"com.example.notes");
        KeyEvent d=richEvent(10000,10000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_2,0,0x80);
        KeyEvent r1=richEvent(10000,10500,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_2,1,0x80);
        KeyEvent r2=richEvent(10000,10600,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_2,2,0x80);
        KeyEvent u=richEvent(10000,10700,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_2,0,0x80);
        dispatch(d,0);Handler.advance(500);dispatch(r1,0);Handler.advance(100);dispatch(r2,0);
        Handler.advance(100);dispatch(u,0);forwarded(d,r1,r2,u);actions();
        eq(0,Handler.pendingCount(),"all-pass hold forwards repeats without long timer");

        begin(all,"com.example.notes");
        u=richEvent(10000,10040,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_2,0,0x80);
        KeyEvent d2=richEvent(10130,10130,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_2,0,0x80);
        KeyEvent u2=richEvent(10130,10170,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_2,0,0x80);
        dispatch(d,0);dispatch(u,0);dispatch(d2,0);dispatch(u2,0);forwarded(d,u,d2,u2);actions();
        eq(0,Handler.pendingCount(),"all-pass double click keeps both complete presses");

        begin(all,"com.example.notes");
        eq(1,dispatch(d,0x01000000),"injected pen DOWN bypasses controller and OEM policy");
        eq(1,dispatch(r1,0x01000000),"injected pen repeat bypasses controller and OEM policy");
        eq(1,dispatch(u,0x01000000),"injected pen UP bypasses controller and OEM policy");
        forwarded();actions();eq(0,Handler.pendingCount(),"forwarded events do not recurse into gestures");

        PenConfig mixed=active(PenConfig.ACTION_PASSTHROUGH);
        begin(mixed,"com.example.notes");
        KeyEvent copiedDown=new KeyEvent(d),copiedUp=new KeyEvent(u);
        dispatch(d,0);dispatch(u,0);forwarded();
        d.overwriteCodeForTest(KeyEvent.KEYCODE_PAGE_UP);u.overwriteCodeForTest(KeyEvent.KEYCODE_PAGE_UP);
        Handler.advance(300);forwarded(copiedDown,copiedUp);actions();
        eq(0,Handler.pendingCount(),"buffered short forwards exactly once");
        d=richEvent(10000,10000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_2,0,0x80);
        u=richEvent(10000,10040,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_2,0,0x80);

        mixed=active(PenConfig.ACTION_VOLUME_UP);mixed.setAction(PenConfig.B1,PenConfig.DOUBLE,PenConfig.ACTION_PASSTHROUGH);
        begin(mixed,"com.example.notes");
        dispatch(d,0);Handler.advance(40);dispatch(u,0);Handler.advance(90);dispatch(d2,0);
        Handler.advance(40);dispatch(u2,0);forwarded();Handler.advance(0);forwarded(d,u,d2,u2);actions();
        Handler.advance(1000);forwarded(d,u,d2,u2);actions();

        mixed=active(PenConfig.ACTION_VOLUME_UP);mixed.setAction(PenConfig.B1,PenConfig.LONG,PenConfig.ACTION_PASSTHROUGH);
        begin(mixed,"com.example.notes");
        KeyEvent earlyRepeat=richEvent(10000,10300,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_2,1,0x80);
        dispatch(d,0);Handler.advance(300);dispatch(earlyRepeat,0);forwarded();
        Handler.advance(100);forwarded(d,earlyRepeat);actions();
        dispatch(r2,0);dispatch(u,0);forwarded(d,earlyRepeat,r2,u);actions();
        Handler.advance(1000);forwarded(d,earlyRepeat,r2,u);

        mixed=active(PenConfig.ACTION_VOLUME_UP);mixed.setAction(PenConfig.B1,PenConfig.LONG,PenConfig.ACTION_HOME);
        mixed.setAction(PenConfig.B1,PenConfig.DOUBLE,PenConfig.ACTION_RECENTS);
        begin(mixed,"com.example.notes");click();Handler.advance(90);yes(down(),"second press begins hold");
        Handler.advance(400);actions(PenConfig.ACTION_HOME);yes(up(),"second long press UP is consumed");
        Handler.advance(1000);actions(PenConfig.ACTION_HOME);forwarded();

        mixed=active(PenConfig.ACTION_VOLUME_UP);mixed.setAction(PenConfig.B1,PenConfig.LONG,PenConfig.ACTION_PASSTHROUGH);
        begin(mixed,"com.example.notes");dispatch(d,0);Handler.advance(40);dispatch(u,0);
        Handler.advance(90);dispatch(d2,0);Handler.advance(400);forwarded(d2);actions();
        KeyEvent secondHoldUp=richEvent(10130,10550,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_2,0,0x80);
        dispatch(secondHoldUp,0);forwarded(d2,secondHoldUp);Handler.advance(1000);forwarded(d2,secondHoldUp);actions();

        mixed=active(PenConfig.ACTION_PASSTHROUGH);begin(mixed,"com.example.notes");
        KeyEvent canceled=richEvent(10000,10040,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_2,0,KeyEvent.FLAG_CANCELED);
        dispatch(d,0);dispatch(canceled,0);Handler.advance(1000);forwarded();actions();
        begin(all,"com.example.notes");dispatch(d,0);dispatch(canceled,0);forwarded(d,canceled);actions();

        begin(mixed,"com.example.notes");dispatch(d,0);dispatch(u,0);focus("com.example.other");
        Handler.advance(1000);forwarded();actions();
        begin(mixed,"com.example.notes");dispatch(d,0);focus("com.example.other");dispatch(r1,0);
        eq(0,dispatch(u,0),"focus change swallows matching original UP");Handler.advance(1000);forwarded();actions();
        begin(mixed,"com.example.notes");dispatch(d,0);
        Method reset=PenKeyController.class.getDeclaredMethod("resetInputState");reset.setAccessible(true);reset.invoke(null);
        Handler.advance(1000);forwarded();actions();eq(null,dispatch(u,0),"removed input device does not leave owned state");
    }
    static boolean reload() throws Exception {
        Method m=PenKeyController.class.getDeclaredMethod("reloadConfig",android.content.Context.class);m.setAccessible(true);
        return (Boolean)m.invoke(null,new android.content.Context());
    }
    static void cacheTests() throws Exception {
        final String key="focuspen_config_snapshot";
        PenConfig first=active(PenConfig.ACTION_VOLUME_UP);
        first.setAppConfig("com.example.notes",passthroughProfile());
        startWatcher(PenConfig.defaults(),"com.example.notes");ContentResolver.snapshot=first.serialize();
        yes(reload(),"provider success reports an authoritative snapshot");
        eq(first.serialize(),android.provider.Settings.Global.values.get(key),"successful provider snapshot is cached");
        eq(1,android.provider.Settings.Global.writes,"first authoritative snapshot writes cache once");
        yes(reload(),"unchanged authoritative snapshot remains available");
        eq(1,android.provider.Settings.Global.writes,"unchanged snapshot does not rewrite global settings");

        field("config").set(null,PenConfig.defaults());field("lastSnapshot").set(null,null);
        ContentResolver.failSnapshot=true;
        yes(!reload(),"cache fallback keeps provider retry status");
        eq(first.serialize(),loaded().serialize(),"restart while provider is stopped restores cached app profiles and actions");
        ContentResolver.failSnapshot=false;
        PenConfig newer=first.copy();newer.setAction(PenConfig.B1,PenConfig.SHORT,PenConfig.ACTION_HOME);
        ContentResolver.snapshot=newer.serialize();yes(reload(),"recovered provider replaces cached configuration");
        eq(newer.serialize(),loaded().serialize(),"provider recovery restores latest actions");

        PenConfig latest=newer.copy();latest.setAction(PenConfig.B1,PenConfig.SHORT,PenConfig.ACTION_RECENTS);
        ContentResolver.snapshot=latest.serialize();android.provider.Settings.Global.failWrite=true;
        reload();
        eq(latest.serialize(),loaded().serialize(),"cache write failure must not revert a successful provider read");
    }
    static final class NativeProbe {
        final List<Boolean> enabled=new ArrayList<>();
        public void setInputFilterEnabled(boolean value){enabled.add(value);}
    }
    static final class ManagerProbe {
        final NativeProbe mNative=new NativeProbe();
        public void start(){}public void setInputFilter(Object filter){}
    }
    static void filterTests() throws Exception {
        begin(passthroughProfile(),"com.example.notes");XposedHelpers.hooks.clear();field("installed").setBoolean(null,false);
        PenKeyController.install(BackendTests.class.getClassLoader());
        for(String name:new String[]{"interceptKeyBeforeQueueing","filterInputEvent","start","setInputFilter"})
            yes(XposedHelpers.hooks.containsKey(name),"controller installs "+name+" hook");
        ManagerProbe manager=new ManagerProbe();
        hook("start",false,manager);eq(0,manager.mNative.enabled.size(),"filter activation waits until system startup has finished");
        hook("start",true,manager);eq(Arrays.asList(true),manager.mNative.enabled,"startup activates native input filtering");
        manager.mNative.setInputFilterEnabled(false);hook("setInputFilter",true,manager,new Object[]{null});
        eq(Arrays.asList(true,false,true),manager.mNative.enabled,"filter replacement is followed by pen filter activation");

        begin(passthroughProfile(),"com.example.notes");
        KeyEvent original=richEvent(10000,10000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_2,0,0x80);
        eq(0,queue(original,0),"owned raw DOWN is consumed at first policy hook");
        eq(1,filteredKeys().size(),"consumed raw DOWN is marked for dispatcher filtering");
        KeyEvent[] different={
            new KeyEvent(10000,10000,0,KeyEvent.KEYCODE_BUTTON_2,0,2,new InputDevice("Xiaomi Smart Pen Keyboard",58),109,0x80,0x101,3),
            richEvent(10000,10001,0,KeyEvent.KEYCODE_BUTTON_2,0,0x80),
            richEvent(10000,10000,0,KeyEvent.KEYCODE_BUTTON_1,0,0x80),
            richEvent(10000,10000,1,KeyEvent.KEYCODE_BUTTON_2,0,0x80),
            richEvent(10000,10000,0,KeyEvent.KEYCODE_BUTTON_2,1,0x80)
        };
        for(KeyEvent other:different) {
            eq(null,filter(other),"different raw event does not match consumption marker");
            eq(0,other.recycleCount,"unmatched raw event remains owned by system filter");
            eq(1,filteredKeys().size(),"unmatched raw event does not remove pending marker");
        }
        InputEvent motion=new InputEvent();eq(null,filter(motion),"non-key input remains untouched");
        eq(0,motion.recycleCount,"non-key input is not recycled by pen hook");
        KeyEvent matching=new KeyEvent(original);
        eq(false,filter(matching),"matching raw DOWN is removed before dispatcher enqueue");
        eq(1,matching.recycleCount,"matching filter callback event is recycled once");
        eq(0,filteredKeys().size(),"matching filter consumes marker once");
        eq(0,original.recycleCount,"policy callback event is not recycled by filter copy");
        KeyEvent again=new KeyEvent(original);eq(null,filter(again),"identical later event cannot reuse consumed marker");
        eq(0,again.recycleCount,"later identical event remains available to system filter");
        KeyEvent up=richEvent(10000,10040,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_2,0,0x80);
        eq(0,queue(up,0),"paired raw UP is consumed by controller");
        eq(false,filter(new KeyEvent(up)),"paired raw UP is removed from original dispatch path");
        eq(0,filteredKeys().size(),"complete raw press leaves no pending markers");

        begin(PenConfig.defaults(),"com.example.notes");
        eq(null,queue(original,0),"unowned raw pen event keeps original system policy");
        eq(0,filteredKeys().size(),"unowned raw pen event creates no filter marker");
        begin(passthroughProfile(),"com.example.notes");
        eq(1,queue(original,0x01000000),"forwarded event bypasses queue policy without consumption");
        eq(0,filteredKeys().size(),"forwarded injected event creates no raw filter marker");
        KeyEvent keyboard=new KeyEvent(0,KeyEvent.KEYCODE_BUTTON_2,0,false,new InputDevice("USB Keyboard",57));
        eq(null,queue(keyboard,0),"ordinary keyboard is not claimed by pen policy");
        eq(0,filteredKeys().size(),"ordinary keyboard creates no filter marker");

        begin(passthroughProfile(),"com.example.notes");
        boolean allConsumed=true;
        for(int i=0;i<128;i++)allConsumed&=Objects.equals(0,queue(richEvent(10000,10000+i,0,KeyEvent.KEYCODE_BUTTON_2,i,0x80),0));
        yes(allConsumed,"bounded marker test starts one owned press followed by repeats");
        eq(128,filteredKeys().size(),"raw consumption markers are bounded at 128 entries");
        KeyEvent newest=richEvent(10000,10128,0,KeyEvent.KEYCODE_BUTTON_2,128,0x80);queue(newest,0);
        eq(1,filteredKeys().size(),"full marker buffer resets before adding newest consumed event");
        eq(null,filter(new KeyEvent(original)),"evicted old marker cannot consume a later duplicate");
        eq(false,filter(new KeyEvent(newest)),"newest marker still filters its matching raw event");

    }
    static void forwardingFailureTests() throws Exception {
        KeyEvent down=richEvent(10000,10000,0,KeyEvent.KEYCODE_BUTTON_2,0,0x80);
        KeyEvent repeat=richEvent(10000,10100,0,KeyEvent.KEYCODE_BUTTON_2,1,0x80);
        KeyEvent up=richEvent(10000,10140,1,KeyEvent.KEYCODE_BUTTON_2,0,0x80);
        begin(passthroughProfile(),"com.example.notes");ActionRunner.failForward=true;
        eq(0,queue(down,0),"failed streaming DOWN remains claimed");eq(1,ActionRunner.forwardAttempts,"streaming DOWN attempts one send");
        cancelled(down);
        eq(0,queue(repeat,0),"failed streaming press consumes repeat without retry");
        eq(0,queue(up,0),"failed streaming press consumes paired UP");
        eq(1,ActionRunner.forwardAttempts,"failed press does not send unpaired repeat or UP");eq(0,Handler.pendingCount(),"failed streaming press leaves no gesture timer");
        ActionRunner.failForward=false;queue(richEvent(10200,10200,0,189,0,0x80),0);queue(richEvent(10200,10240,1,189,0,0x80),0);
        eq(2,ActionRunner.forwarded.size(),"next streaming press recovers after previous injection failure");

        begin(passthroughProfile(),"com.example.notes");queue(down,0);ActionRunner.failForward=true;
        eq(0,queue(up,0),"failed streaming UP remains claimed");eq(0,Handler.pendingCount(),"failed UP leaves no delayed work");
        cancelled(down);
        ActionRunner.failForward=false;queue(richEvent(10200,10200,0,189,0,0x80),0);queue(richEvent(10200,10240,1,189,0,0x80),0);
        eq(3,ActionRunner.forwarded.size(),"new press recovers after streaming UP failure");

        PenConfig shortPass=active(PenConfig.ACTION_PASSTHROUGH);begin(shortPass,"com.example.notes");queue(down,0);queue(up,0);
        ActionRunner.failForward=true;Handler.advance(300);eq(1,ActionRunner.forwardAttempts,"short timer reports failed buffered send");
        cancelled();
        eq(0,Handler.pendingCount(),"short timer failure is contained and clears tasks");
        ActionRunner.failForward=false;queue(richEvent(10400,10400,0,189,0,0x80),0);queue(richEvent(10400,10440,1,189,0,0x80),0);Handler.advance(300);
        eq(2,ActionRunner.forwarded.size(),"short gesture recovers after timer injection failure");

        PenConfig longPass=active(PenConfig.ACTION_VOLUME_UP);longPass.setAction(PenConfig.B1,PenConfig.LONG,PenConfig.ACTION_PASSTHROUGH);
        begin(longPass,"com.example.notes");queue(down,0);ActionRunner.failForward=true;Handler.advance(400);
        eq(1,ActionRunner.forwardAttempts,"long timer reports failed buffered send");
        cancelled(down);
        eq(0,queue(repeat,0),"failed long gesture consumes subsequent repeat");eq(0,queue(up,0),"failed long gesture consumes subsequent UP");
        eq(1,ActionRunner.forwardAttempts,"failed long gesture does not resume partial forwarding");eq(0,Handler.pendingCount(),"long timer failure is contained");

        PenConfig doublePass=active(PenConfig.ACTION_VOLUME_UP);doublePass.setAction(PenConfig.B1,PenConfig.DOUBLE,PenConfig.ACTION_PASSTHROUGH);
        begin(doublePass,"com.example.notes");queue(down,0);queue(up,0);Handler.advance(90);
        queue(richEvent(10200,10200,0,189,0,0x80),0);queue(richEvent(10200,10240,1,189,0,0x80),0);
        ActionRunner.failForward=true;Handler.advance(0);eq(1,ActionRunner.forwardAttempts,"double callback contains failed buffered send");eq(0,Handler.pendingCount(),"double callback failure clears delayed work");
        cancelled();
        forwarded();actions();
    }
    static void nativeCancellationTests() throws Exception {
        KeyEvent down=richEvent(10000,10000,0,189,0,0x80),up=richEvent(10000,10700,1,189,0,0x80);
        KeyEvent repeat=richEvent(10000,10600,0,189,1,0x80);
        begin(passthroughProfile(),"com.example.notes");queue(down,0);focus("com.example.notes");cancelled();
        KeyEvent saved=new KeyEvent(down);down.overwriteCodeForTest(188);focus("com.example.other");cancelled(saved);forwarded(saved);
        focus("com.example.third");cancelled(saved);
        eq(0,queue(repeat,0),"cancelled focus consumes original repeat");eq(0,queue(up,0),"cancelled focus consumes original UP");
        cancelled(saved);forwarded(saved);Handler.advance(1000);cancelled(saved);forwarded(saved);
        down=richEvent(10000,10000,0,189,0,0x80);

        begin(passthroughProfile(),"com.example.notes");queue(down,0);queue(up,0);focus("com.example.other");cancelled();
        forwarded(down,up);
        begin(active(PenConfig.ACTION_VOLUME_UP),"com.example.notes");queue(down,0);focus("com.example.other");cancelled();
        Handler.advance(1000);forwarded();actions();

        PenConfig longPass=active(PenConfig.ACTION_VOLUME_UP);longPass.setAction(PenConfig.B1,PenConfig.LONG,PenConfig.ACTION_PASSTHROUGH);
        begin(longPass,"com.example.notes");queue(down,0);Handler.advance(400);forwarded(down);focus("com.example.other");cancelled(down);
        eq(0,queue(up,0),"cancelled long press consumes hardware UP");forwarded(down);cancelled(down);

        begin(passthroughProfile(),"com.example.notes");queue(down,0);ActionRunner.failCancel=true;focus("com.example.other");
        eq(1,ActionRunner.cancelAttempts,"focus cancellation attempts native cleanup once despite rejection");
        eq(0,queue(repeat,0),"failed cancellation still swallows original repeat");eq(0,queue(up,0),"failed cancellation still swallows original UP");
        eq(1,ActionRunner.cancelAttempts,"failed cleanup cannot repeatedly send releases");
        ActionRunner.failCancel=false;queue(richEvent(11200,11200,0,189,0,0x80),0);queue(richEvent(11200,11240,1,189,0,0x80),0);
        eq(3,ActionRunner.forwarded.size(),"next press recovers after cancellation rejection");
    }
    public static void main(String[] args) throws Exception {
        configTests();controllerTests();portMappingTests();observerTests();passthroughTests();cacheTests();filterTests();forwardingFailureTests();nativeCancellationTests();
        System.out.println("PASS " + checks + " backend assertions (production parser/controller; forwarding requests, not Android injection)");
    }
}
