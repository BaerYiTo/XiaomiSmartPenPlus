package xiaomismartpen.plus;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.content.ContentResolver;
import de.robv.android.xposed.XC_MethodHook;
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
        ((Map<?,?>)field("STATES").get(null)).clear(); Handler.reset(); ActionRunner.actions.clear();
        ContentResolver.reset();
        field("handler").set(null,new Handler(Looper.getMainLooper())); field("configHandler").set(null,null);
        field("config").set(null,p.copy()); field("configLoadedAt").setLong(null,Handler.now);
        field("cachedContext").set(null,new android.content.Context());
        field("focusedPackage").set(null,null); field("focusEpoch").setLong(null,0L); focus(pkg);
    }
    static boolean event(int action, int repeat, boolean cancelled, InputDevice device) throws Exception {
        Method m=PenKeyController.class.getDeclaredMethod("handle", XC_MethodHook.MethodHookParam.class, KeyEvent.class); m.setAccessible(true);
        XC_MethodHook.MethodHookParam param=new XC_MethodHook.MethodHookParam();
        m.invoke(null,param,new KeyEvent(action,KeyEvent.KEYCODE_PAGE_DOWN,repeat,cancelled,device));
        return Objects.equals(0,param.result);
    }
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
    public static void main(String[] args) throws Exception { configTests(); controllerTests(); observerTests(); System.out.println("PASS " + checks + " backend assertions (production parser and controller)"); }
}
