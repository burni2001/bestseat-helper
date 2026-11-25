package tv.bestseat.helper;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Autostart: starts chosen apps with the TV and optionally keeps them running.
 *
 * Per app: switch (on = start with the TV), "Start" in the background or in front, "Keep running".
 * - With the TV: after boot (60 s delay, the TV is busy for a long time after boot) and after
 *   every wake from standby (10 s). If the app is already running, nothing happens.
 * - Background: most apps only start through their window. The helper opens the app, waits
 *   until it is in front, then brings back the app that was in front before.
 *   Never while something is playing or live TV/HDMI is in front (the switch would interrupt
 *   the stream or the HDMI input). Then it retries every 30 s.
 * - In front: only when starting with the TV and only if the home screen is in front (so not
 *   over a console on HDMI that woke the TV via CEC). With several apps, the last one wins.
 * - Keep running: every 30 s while the screen is on, check (pidof) and quietly restart stopped
 *   apps in the background, even if they normally start in front. At most 3 restarts in
 *   10 minutes per app, then the guard gives up on it (the TV keeps killing background apps
 *   when memory is low; without a limit they would race).
 *
 * Queries (front app, playback, running processes) go through the app's own adb access (AdbClient).
 */
final class AutostartHelper extends Helper {

    private static final String PLAYTV = "org.droidtv.playtv";
    private static final String LAUNCHERX = "com.google.android.apps.tv.launcherx";
    private static final long TICK_MS = 30000;
    private static final long BOOT_WINDOW_MS = 5 * 60 * 1000L;
    private static final long BOOT_DELAY_MS = 60000;
    private static final long WAKE_DELAY_MS = 10000;
    private static final int MAX_RESTARTS = 3;
    private static final long RESTART_WINDOW_MS = 10 * 60 * 1000L;
    private static final String KEY_APPS = "apps";
    private static final String APP = "app:";
    private static final String MODE = "mode:";
    private static final String WATCH = "watch:";

    /** Apps still to start after boot or wake. */
    private final Set<String> pending = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> gaveUp = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, List<Long>> restarts = Collections.synchronizedMap(new HashMap<>());
    private volatile boolean foregroundPending;
    private volatile boolean busy;
    private volatile String lastAction;
    private volatile String lastError;
    private final Runnable tick = this::tick;

    @Override
    String id() {
        return "autostart";
    }

    @Override
    String title() {
        return "Autostart";
    }

    @Override
    String description() {
        return "Start apps with the TV and keep them running.";
    }

    @Override
    int icon() {
        return android.R.drawable.ic_media_play;
    }

    /** The service keeps the process alive so the guard also checks in the background. */
    @Override
    boolean needsService() {
        return true;
    }

    // ------------------------------------------------------------------ UI

    @Override
    List<Setting> settings() {
        List<Setting> list = new ArrayList<>();
        for (String pkg : apps()) {
            list.add(new Setting(APP + pkg, ActionPicker.label(Hub.app, pkg), true,
                    "Start this app when the TV turns on."));
        }
        return list;
    }

    @Override
    void onSettingChanged(String key, boolean value) {
        if (value && key.startsWith(APP)) {
            pending.add(key.substring(APP.length()));
        }
        kick();
    }

    @Override
    List<Choice> choices() {
        List<Choice> list = new ArrayList<>();
        for (String pkg : apps()) {
            list.add(new Choice("Start",
                    () -> inFront(pkg) ? "In front" : "In background",
                    () -> "Choose whether the app opens in front or starts quietly behind what is on screen.",
                    (activity, done) -> OptionPicker.show(activity, ActionPicker.label(activity, pkg),
                            "Choose how this app starts with the TV.",
                            new String[] {"In background", "In front", "Remove from list"},
                            inFront(pkg) ? 1 : 0, i -> {
                                if (i == 2) {
                                    remove(pkg);
                                    activity.recreate();
                                    return;
                                }
                                Prefs.setString(Hub.app, this, MODE + pkg, i == 1 ? "fg" : "bg");
                                done.run();
                            })).after(APP + pkg));
            list.add(new Choice("Keep running",
                    () -> watched(pkg) ? "On" : "Off",
                    () -> "Restart this app whenever it closes, without interrupting what you watch.",
                    (activity, done) -> {
                        boolean on = !watched(pkg);
                        Prefs.setSetting(Hub.app, this, WATCH + pkg, on);
                        gaveUp.remove(pkg);
                        restarts.remove(pkg);
                        done.run();
                        kick();
                    }).after(APP + pkg));
        }
        list.add(new Choice("Add app",
                () -> apps().isEmpty() ? "None yet" : "",
                () -> "Pick an app to start with the TV.",
                (activity, done) -> pickApp(activity)));
        return list;
    }

    @Override
    String detailNote() {
        return "Apps that start in the background open briefly, then the app you were using comes back.";
    }

    private void pickApp(Activity a) {
        LinearLayout panel = Dialogs.panel(a);
        Dialog dialog = Dialogs.show(a, panel);
        panel.addView(Ui.text(a, "Add app", 26, Ui.TEXT, true));
        TextView sub = Ui.text(a, "Pick an app to start with the TV.", 14, Ui.TEXT_DIM, false);
        sub.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 14));
        panel.addView(sub);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4));
        ScrollView scroll = new ScrollView(a);
        scroll.setClipChildren(false);
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 330)));
        List<String> chosen = apps();
        View first = null;
        for (String[] app : ActionPicker.apps(a)) {
            String pkg = app[1];
            if (chosen.contains(pkg)) {
                continue;
            }
            View row = ActionPicker.item(a, list, app[0], "", false, () -> {
                add(pkg);
                dialog.dismiss();
                a.recreate();
            });
            if (first == null) {
                first = row;
            }
        }
        dialog.show();
        if (first != null) {
            first.requestFocus();
        }
    }

    // ------------------------------------------------------------------ List

    private List<String> apps() {
        String s = Prefs.string(Hub.app, this, KEY_APPS);
        List<String> list = new ArrayList<>();
        if (s != null && !s.isEmpty()) {
            list.addAll(Arrays.asList(s.split(",")));
        }
        return list;
    }

    private void saveApps(List<String> list) {
        Prefs.setString(Hub.app, this, KEY_APPS, String.join(",", list));
    }

    private void add(String pkg) {
        List<String> list = apps();
        if (!list.contains(pkg)) {
            list.add(pkg);
            saveApps(list);
            Hub.log("Autostart: added " + pkg);
        }
    }

    private void remove(String pkg) {
        List<String> list = apps();
        list.remove(pkg);
        saveApps(list);
        Prefs.get(Hub.app).edit()
                .remove(id() + "." + APP + pkg)
                .remove(id() + "." + MODE + pkg)
                .remove(id() + "." + WATCH + pkg)
                .apply();
        pending.remove(pkg);
        gaveUp.remove(pkg);
        restarts.remove(pkg);
        Hub.log("Autostart: removed " + pkg);
    }

    private boolean enabled(String pkg) {
        return Prefs.setting(Hub.app, this, APP + pkg, true);
    }

    private boolean inFront(String pkg) {
        return "fg".equals(Prefs.string(Hub.app, this, MODE + pkg));
    }

    private boolean watched(String pkg) {
        return Prefs.setting(Hub.app, this, WATCH + pkg, false);
    }

    private List<String> enabledApps() {
        List<String> list = new ArrayList<>();
        for (String pkg : apps()) {
            if (enabled(pkg) && launchIntent(pkg) != null) {
                list.add(pkg);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ Lifecycle

    @Override
    void start() {
        gaveUp.clear();
        restarts.clear();
        withTheTv(SystemClock.elapsedRealtime() < BOOT_WINDOW_MS ? BOOT_DELAY_MS : 3000);
    }

    @Override
    void stop() {
        Hub.handler.removeCallbacks(tick);
        pending.clear();
        foregroundPending = false;
    }

    @Override
    void onScreenChanged(boolean on) {
        if (on) {
            withTheTv(WAKE_DELAY_MS);
        } else {
            Hub.handler.removeCallbacks(tick);
            pending.clear();
            foregroundPending = false;
        }
    }

    /** Start "with the TV": queue all enabled apps, bring the front app to the front once. */
    private void withTheTv(long delay) {
        pending.clear();
        pending.addAll(enabledApps());
        foregroundPending = true;
        Hub.handler.removeCallbacks(tick);
        Hub.handler.postDelayed(tick, delay);
    }

    private void kick() {
        if (Hub.isRunning(this)) {
            Hub.handler.removeCallbacks(tick);
            Hub.handler.postDelayed(tick, 1000);
        }
    }

    @Override
    Status runningStatus() {
        List<String> starts = new ArrayList<>();
        List<String> keeps = new ArrayList<>();
        for (String pkg : enabledApps()) {
            starts.add(ActionPicker.label(Hub.app, pkg));
            if (watched(pkg)) {
                keeps.add(ActionPicker.label(Hub.app, pkg));
            }
        }
        if (starts.isEmpty()) {
            return Status.idle("No apps chosen", "Add apps below.");
        }
        if (lastError != null) {
            return Status.bad("Error", lastError);
        }
        List<String> failed = new ArrayList<>();
        for (String pkg : gaveUp.toArray(new String[0])) {
            failed.add(ActionPicker.label(Hub.app, pkg));
        }
        if (!failed.isEmpty()) {
            return Status.bad("Gave up: " + String.join(", ", failed),
                    "Closed " + MAX_RESTARTS + " times in 10 minutes. Switch Keep running off and on to try again.");
        }
        String text = "Starts " + String.join(", ", starts)
                + (keeps.isEmpty() ? "" : ". Keeps running: " + String.join(", ", keeps));
        return Status.good("Active", text + (lastAction != null ? ". Last: " + lastAction : ""));
    }

    // ------------------------------------------------------------------ Flow

    private void tick() {
        if (!Hub.isRunning(this) || !Hub.screenOn() || busy) {
            return;
        }
        List<String> enabled = enabledApps();
        List<String> wanted = new ArrayList<>();
        for (String pkg : enabled) {
            if (pending.contains(pkg) || (watched(pkg) && !gaveUp.contains(pkg))) {
                wanted.add(pkg);
            }
        }
        if (wanted.isEmpty()) {
            pending.clear();
            foregroundPending = false;
            return;
        }
        Map<String, Boolean> front = new HashMap<>();
        Map<String, Boolean> watch = new HashMap<>();
        for (String pkg : enabled) {
            front.put(pkg, inFront(pkg));
            watch.put(pkg, watched(pkg));
        }
        boolean fgPending = foregroundPending;
        busy = true;
        new Thread(() -> {
            try {
                run(enabled, wanted, front, watch, fgPending);
                lastError = null;
            } catch (Exception e) {
                lastError = "Debugging access failed: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                Log.w(Hub.TAG, "Autostart", e);
            }
            Hub.handler.post(() -> {
                busy = false;
                boolean more = !pending.isEmpty();
                for (String pkg : enabledApps()) {
                    more |= watched(pkg) && !gaveUp.contains(pkg);
                }
                if (more && Hub.isRunning(this) && Hub.screenOn()) {
                    Hub.handler.removeCallbacks(tick);
                    Hub.handler.postDelayed(tick, TICK_MS);
                }
            });
        }, "Autostart").start();
    }

    /** Runs on its own thread. */
    private void run(List<String> enabled, List<String> wanted, Map<String, Boolean> front,
                     Map<String, Boolean> watch, boolean fgPending) throws Exception {
        State s = query(enabled);
        String fgApp = null;
        if (fgPending) {
            for (String pkg : enabled) {
                if (front.get(pkg) && pending.contains(pkg)) {
                    fgApp = pkg;
                }
            }
        }
        for (String pkg : wanted) {
            if (pkg.equals(fgApp)) {
                continue;
            }
            if (s.running.contains(pkg)) {
                pending.remove(pkg);
                continue;
            }
            boolean restart = !pending.contains(pkg);
            if (!s.idle()) {
                Log.i(Hub.TAG, "Autostart: " + pkg + " waits, " + (s.playing ? "something is playing" : "live TV is in front"));
                continue;
            }
            if (restart && !allowRestart(pkg)) {
                continue;
            }
            if (startQuietly(pkg, s)) {
                pending.remove(pkg);
                note((restart ? "restarted " : "started ") + ActionPicker.label(Hub.app, pkg));
            }
            s = query(enabled);
        }
        if (fgPending) {
            foregroundPending = false;
            if (fgApp != null) {
                pending.remove(fgApp);
                if (isHome(s.frontPkg)) {
                    Hub.log("Autostart: opening " + fgApp + " in front");
                    Hub.app.startActivity(launchIntent(fgApp));
                    note("opened " + ActionPicker.label(Hub.app, fgApp));
                } else if (!s.running.contains(fgApp) && s.idle()) {
                    // Something else is in front (e.g. another app): then only start quietly.
                    if (startQuietly(fgApp, s)) {
                        note("started " + ActionPicker.label(Hub.app, fgApp));
                    }
                } else {
                    Log.i(Hub.TAG, "Autostart: " + fgApp + " not opened, " + s.frontPkg + " is in front");
                }
            }
        }
    }

    private boolean allowRestart(String pkg) {
        long now = System.currentTimeMillis();
        List<Long> times = restarts.computeIfAbsent(pkg, x -> new ArrayList<>());
        times.removeIf(t -> now - t > RESTART_WINDOW_MS);
        if (times.size() >= MAX_RESTARTS) {
            gaveUp.add(pkg);
            Hub.log("Autostart: " + pkg + " closed " + MAX_RESTARTS + " times in 10 minutes, giving up");
            return false;
        }
        times.add(now);
        return true;
    }

    /** Opens the app, waits until it is in front, then brings back the previous app. */
    private boolean startQuietly(String pkg, State before) throws Exception {
        Intent launch = launchIntent(pkg);
        if (launch == null) {
            return false;
        }
        Hub.log("Autostart: starting " + pkg + " in the background (in front: " + before.frontPkg + ")");
        Hub.app.startActivity(launch);
        // Wait until the app is in front and past its splash screen. Some apps show a splash
        // activity for about 6 s and then start their main activity themselves; if the helper
        // came back earlier, that activity later covered it.
        long until = SystemClock.elapsedRealtime() + 12000;
        boolean shown = false;
        while (SystemClock.elapsedRealtime() < until) {
            Thread.sleep(500);
            String[] top = top(AdbClient.shell(Hub.app, "dumpsys activity activities | grep -m1 topResumedActivity", 10000));
            if (top != null && pkg.equals(top[0])) {
                shown = true;
                if (!top[1].toLowerCase(Locale.US).contains("splash")) {
                    break;
                }
            }
        }
        // Let it run briefly so the app can start its services.
        Thread.sleep(shown ? 1500 : 0);
        if (before.frontPkg != null && !before.frontPkg.equals(pkg)) {
            bringBack(before.frontPkg, before.frontCls);
        }
        return true;
    }

    private void bringBack(String pkg, String cls) {
        Intent intent = null;
        if (isHome(pkg)) {
            intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
        } else if (cls != null) {
            try {
                ActivityInfo ai = Hub.app.getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
                if (Hub.app.getPackageName().equals(pkg) || (ai.exported && ai.enabled && ai.permission == null)) {
                    intent = new Intent().setComponent(new ComponentName(pkg, cls))
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                // Then through the app's start page.
            }
        }
        // System pages like the "USB storage missing" notice on wake (NewStorageActivity) can't
        // be started from outside. Their app's start page would show the TV settings instead, so
        // for system apps go to the TV's home screen.
        if (intent == null && !isSystem(pkg)) {
            intent = launchIntent(pkg);
        }
        if (intent == null) {
            intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            Hub.app.startActivity(intent);
            Log.i(Hub.TAG, "Autostart: brought back " + pkg + (cls != null ? "/" + cls : ""));
        } catch (RuntimeException e) {
            Log.w(Hub.TAG, "Autostart: could not bring back " + pkg, e);
        }
    }

    private static boolean isSystem(String pkg) {
        try {
            return (Hub.app.getPackageManager().getApplicationInfo(pkg, 0).flags
                    & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private void note(String what) {
        lastAction = new SimpleDateFormat("HH:mm", Locale.US).format(new Date()) + " " + what;
    }

    // ------------------------------------------------------------------ Queries

    /** TV state: front app, playback, running apps. */
    private State query(List<String> pkgs) throws Exception {
        StringBuilder cmd = new StringBuilder("dumpsys activity activities | grep -m1 topResumedActivity; "
                + "echo playing=$(dumpsys media_session | grep -c 'state=PLAYING')");
        if (!pkgs.isEmpty()) {
            cmd.append("; for p in ").append(String.join(" ", pkgs))
                    .append("; do pidof $p >/dev/null && echo run=$p; done");
        }
        String out = AdbClient.shell(Hub.app, cmd.toString(), 10000);
        State s = new State();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.contains("topResumedActivity")) {
                String[] top = top(line);
                if (top != null) {
                    s.frontPkg = top[0];
                    s.frontCls = top[1];
                }
            } else if (line.startsWith("playing=")) {
                try {
                    s.playing = Integer.parseInt(line.substring(8).trim()) > 0;
                } catch (NumberFormatException ignored) {
                    // Then treat as "nothing playing".
                }
            } else if (line.startsWith("run=")) {
                s.running.add(line.substring(4).trim());
            }
        }
        return s;
    }

    /** "…ActivityRecord{abc u0 package/.Class t1}" to {package, full class}. */
    private static String[] top(String line) {
        if (line == null) {
            return null;
        }
        for (String part : line.split("[\\s{}]+")) {
            int slash = part.indexOf('/');
            if (slash > 0) {
                String pkg = part.substring(0, slash);
                String cls = part.substring(slash + 1);
                return new String[] {pkg, cls.startsWith(".") ? pkg + cls : cls};
            }
        }
        return null;
    }

    private static boolean isHome(String pkg) {
        if (pkg == null) {
            return false;
        }
        if (LAUNCHERX.equals(pkg)) {
            return true;
        }
        try {
            ResolveInfo ri = Hub.app.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Intent launchIntent(String pkg) {
        PackageManager pm = Hub.app.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) {
            i = pm.getLaunchIntentForPackage(pkg);
        }
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return i;
    }
}
