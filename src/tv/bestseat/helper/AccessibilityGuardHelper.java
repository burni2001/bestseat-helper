package tv.bestseat.helper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Accessibility guard: turns watched accessibility services back on as soon as they drop out of
 * enabled_accessibility_services, e.g. after a force stop or when the list was cleared.
 *
 * Only adds, never removes: read the current list, append missing watched services. At most
 * 3 corrections per service in 10 minutes, then the guard gives up on it. Which services are
 * watched is a switch per service on the detail page. To turn a service off on purpose,
 * remove it here first.
 *
 * Permission: pm grant tv.bestseat.helper android.permission.WRITE_SECURE_SETTINGS
 */
final class AccessibilityGuardHelper extends Helper {

    private static final List<String> DEFAULT_WATCHED = Arrays.asList(
            "com.overdevs.at4k", "dev.vodik7.tvquickactions.free", "tv.bestseat.helper");
    /**
     * Not in the list: Android Accessibility Suite with TalkBack and Switch Access. Turning those
     * on through the guard would change how the whole TV is operated.
     */
    private static final List<String> HIDDEN = Arrays.asList("com.google.android.marvin.talkback");
    private static final String KEY_PREFIX = "watch:";
    private static final int MAX_FIXES = 3;
    private static final long FIX_WINDOW_MS = 10 * 60 * 1000L;

    private SettingsObserver observer;
    private final Runnable onChanged = () -> repair("list changed");
    private final Runnable onScreenOn = () -> repair("screen on");
    private final Map<String, List<Long>> fixes = new HashMap<>();
    private final Set<String> gaveUp = new LinkedHashSet<>();
    private String lastFix;

    @Override
    String id() {
        return "accessibility_guard";
    }

    @Override
    String title() {
        return "Accessibility guard";
    }

    @Override
    String description() {
        return "Turn accessibility services back on when they drop out, e.g. after Force stop.";
    }

    @Override
    int icon() {
        return android.R.drawable.ic_menu_view;
    }

    /** The service keeps the process alive so the guard listens even without the accessibility service. */
    @Override
    boolean needsService() {
        return true;
    }

    @Override
    String missingPermission(Context context) {
        return Perms.writeSecure(context) ? null : "Secure settings permission is missing";
    }

    /** One "watch" switch per installed accessibility service. Disabled apps are missing here. */
    @Override
    List<Setting> settings() {
        List<ComponentName> services = installed();
        Map<String, Integer> perPackage = new HashMap<>();
        for (ComponentName c : services) {
            perPackage.merge(c.getPackageName(), 1, Integer::sum);
        }
        List<Setting> list = new ArrayList<>();
        for (ComponentName c : services) {
            String label = appLabel(c.getPackageName());
            if (perPackage.get(c.getPackageName()) > 1) {
                label += " · " + c.getShortClassName().substring(c.getShortClassName().lastIndexOf('.') + 1);
            }
            list.add(new Setting(KEY_PREFIX + c.flattenToShortString(), label,
                    DEFAULT_WATCHED.contains(c.getPackageName()),
                    "Turn this service back on whenever it drops out, e.g. after Force stop."));
        }
        list.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        return list;
    }

    @Override
    void onSettingChanged(String key, boolean value) {
        if (value && key.startsWith(KEY_PREFIX)) {
            gaveUp.remove(key.substring(KEY_PREFIX.length()));
            fixes.remove(key.substring(KEY_PREFIX.length()));
            if (Hub.isRunning(this)) {
                repair("setting");
            }
        }
    }

    @Override
    String detailNote() {
        return "Switch a service off here first before you turn it off on purpose. The guard never turns any off.";
    }

    @Override
    void start() {
        fixes.clear();
        gaveUp.clear();
        observer = new SettingsObserver(Hub.handler, () -> {
            Hub.handler.removeCallbacks(onChanged);
            Hub.handler.postDelayed(onChanged, 2000);
        });
        Hub.app.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer);
        repair("start");
    }

    @Override
    void stop() {
        if (observer != null) {
            Hub.app.getContentResolver().unregisterContentObserver(observer);
            observer = null;
        }
        Hub.handler.removeCallbacks(onChanged);
        Hub.handler.removeCallbacks(onScreenOn);
    }

    @Override
    void onScreenChanged(boolean on) {
        if (on) {
            Hub.handler.removeCallbacks(onScreenOn);
            Hub.handler.postDelayed(onScreenOn, 5000);
        }
    }

    @Override
    Status runningStatus() {
        List<String> watched = new ArrayList<>();
        List<String> off = new ArrayList<>();
        Set<ComponentName> on = enabled();
        for (Setting s : settings()) {
            if (Prefs.setting(Hub.app, this, s)) {
                watched.add(s.title);
                if (!on.contains(component(s))) {
                    off.add(s.title);
                }
            }
        }
        if (!gaveUp.isEmpty()) {
            return Status.bad("Gave up: " + String.join(", ", off.isEmpty() ? watched : off),
                    "Turned off " + MAX_FIXES + " times in 10 minutes. Turn the guard off and on to try again.");
        }
        if (watched.isEmpty()) {
            return Status.idle("Nothing watched", "Choose services in the settings.");
        }
        return Status.good("Active", "Watching " + String.join(", ", watched)
                + (lastFix != null ? ". Last fixed " + lastFix : ""));
    }

    // ------------------------------------------------------------------ Repair

    private void repair(String reason) {
        if (!Perms.writeSecure(Hub.app)) {
            return;
        }
        ContentResolver cr = Hub.app.getContentResolver();
        String current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (current == null) {
            current = "";
        }
        Set<ComponentName> on = parse(current);
        long now = System.currentTimeMillis();
        List<String> add = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Setting s : settings()) {
            if (!Prefs.setting(Hub.app, this, s)) {
                continue;
            }
            ComponentName c = component(s);
            if (c == null || on.contains(c)) {
                continue;
            }
            String k = c.flattenToShortString();
            if (gaveUp.contains(k)) {
                continue;
            }
            List<Long> times = fixes.computeIfAbsent(k, x -> new ArrayList<>());
            times.removeIf(t -> now - t > FIX_WINDOW_MS);
            if (times.size() >= MAX_FIXES) {
                gaveUp.add(k);
                Hub.log("Accessibility guard: " + s.title + " turned off " + MAX_FIXES + " times in 10 minutes, giving up");
                continue;
            }
            times.add(now);
            add.add(c.flattenToShortString());
            names.add(s.title);
        }
        if (add.isEmpty()) {
            return;
        }
        String next = current.trim().isEmpty() ? String.join(":", add) : current + ":" + String.join(":", add);
        try {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (RuntimeException e) {
            Log.w(Hub.TAG, "Accessibility guard: could not write", e);
            return;
        }
        Hub.log("Accessibility guard (" + reason + "): turned back on " + names);
        lastFix = new SimpleDateFormat("HH:mm", Locale.US).format(new Date()) + " (" + String.join(", ", names) + ")";
    }

    // ------------------------------------------------------------------ Helpers

    private static List<ComponentName> installed() {
        List<ComponentName> list = new ArrayList<>();
        AccessibilityManager am = Hub.app.getSystemService(AccessibilityManager.class);
        for (AccessibilityServiceInfo info : am.getInstalledAccessibilityServiceList()) {
            ServiceInfo si = info.getResolveInfo().serviceInfo;
            if (!HIDDEN.contains(si.packageName)) {
                list.add(new ComponentName(si.packageName, si.name));
            }
        }
        return list;
    }

    private static Set<ComponentName> enabled() {
        return parse(Settings.Secure.getString(Hub.app.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
    }

    /** "a/.B:c/d.E" to components (short class names are completed). */
    private static Set<ComponentName> parse(String list) {
        Set<ComponentName> set = new LinkedHashSet<>();
        if (list == null) {
            return set;
        }
        for (String part : list.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(part.trim());
            if (c != null) {
                set.add(c);
            }
        }
        return set;
    }

    private static ComponentName component(Setting s) {
        return ComponentName.unflattenFromString(s.key.substring(KEY_PREFIX.length()));
    }

    private static String appLabel(String pkg) {
        PackageManager pm = Hub.app.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
