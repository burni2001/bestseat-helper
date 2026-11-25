package tv.bestseat.helper;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Watches what happens after an app key press.
 *
 * For 6 s it polls usage stats every 80 ms (and at once on every window change of the
 * target app) to see whether the hard-wired app comes to the front. The accessibility window
 * event only triggers a poll; it arrives too late (1–1.3 s) to count as "in front" itself.
 * If the target app shows up, the app directly below it comes back:
 * - the app that came to the front after the press (e.g. an action of another key app), else
 * - the app that was in front when the key was pressed.
 * If another app comes to the front after the target app, the user's action has already won
 * and nothing happens.
 *
 * Only the Netflix key remembers a target (app and activity, see Keys.remembers), editable on
 * the detail page. If Netflix comes up before the action, that target comes back. If the action
 * only opens an invisible activity, the remembered target is cleared.
 *
 * Any other key ends the guard at once. Invisible trampoline activities (Theme.NoDisplay)
 * don't count as apps.
 *
 * Starting activities from the background is allowed because the system binds the app as an
 * accessibility service (it also has "Display over other apps").
 */
final class AppGuard implements Runnable {

    private static final String TAG = AppKeysHelper.TAG;
    private static final long WATCH_MS = 6000;
    private static final long POLL_MS = 80;
    /** How long the previous app must stay in front before the remembered target goes on top. */
    private static final long SETTLE_MS = 350;
    /** After this time the remembered target comes anyway. */
    private static final long SETTLE_MAX_MS = 2500;
    /**
     * Live TV/HDMI needs longer until a picture is back. The input is reopened, so the picture
     * returns quickly. Must stay below WATCH_MS, or the guard ends first.
     */
    private static final String PLAYTV = "org.droidtv.playtv";
    private static final long SETTLE_PLAYTV_MS = 1500;
    private static final long SETTLE_PLAYTV_MAX_MS = 3000;
    /** Look back a short time first (fast); only if nothing is found, 12 hours. */
    private static final long LOOKBACK_SHORT_MS = 30L * 60 * 1000;
    private static final long LOOKBACK_LONG_MS = 12L * 60 * 60 * 1000;
    /**
     * For this long after the last restore, the target app is closed. Otherwise it stayed open
     * directly below the restored app, and Back there showed it again.
     */
    private static final long CLOSE_AFTER_MS = 700;
    private static final String TVQUICKACTIONS = "dev.vodik7.tvquickactions.free";
    private static final String TVQUICKACTIONS_TRAMPOLINE = "dev.vodik7.tvquickactions.MainActivity";

    private static AppGuard current;
    private static final Map<String, Boolean> TRANSIENT = new HashMap<>();
    /** Home activities of apps that are not the selected launcher (e.g. FallbackHome). */
    private static final Map<String, Boolean> STRAY_HOME = new HashMap<>();
    /**
     * Last seen HDMI input ("hdmi1"). Without a session to the input the setting is empty,
     * e.g. already at the moment of a quick second press.
     */
    private static String lastHdmiSource;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final UsageStatsManager usageStats;
    private final PackageManager pm;
    private final int key;
    private final String target;
    private final long pressedAt;
    /**
     * Usage stats are read from here on. Normally the press; for quick repeated presses of the
     * same key the first one, so an app that came up between two presses is still seen.
     */
    private final long since;
    /** The target app came to the front since "since" (then it is closed even without a restore). */
    private boolean targetOpened;
    /** Replaced by a new guard for the same key: the new one closes the app. */
    private boolean superseded;
    /**
     * The target app was in front at the press and is not closed,
     * e.g. watching Netflix and opening the picture menu with the Netflix key.
     */
    private final boolean targetWasOpen;
    /**
     * Target app in front at the press: it doesn't come to the front again (the key is only sent to
     * it), so the guard sees nothing. The remembered target still goes on top after this delay.
     */
    private static final long OVER_TARGET_MS = 700;
    private boolean openedOverTarget;
    /** App in front at the press, null if that was the target app itself. */
    private final String before;
    /** App that was in front before "before" (other than before), or null. */
    private String underneath;
    /** Remembered target of the key (Netflix only), or null. */
    private final String learned;
    private final String learnedClass;
    /** From the last poll: app or invisible activity after the press, before the target app. */
    private String actionApp;
    /** Remembered target that still has to open after the previous app. */
    private String pendingTarget;
    private String pendingBase;
    private long pendingSince;
    private String actionClass;
    private boolean actionTransient;
    /** Package of the selected launcher, or null. */
    private final String defaultHome;
    /** Time of the target app activation that is already handled. */
    private long handledResume;
    private String lastTrace = "";
    /** The target app was sent away and should be closed. */
    private boolean sentAway;
    private long sentAwayAt;
    /**
     * Time of the last close, 0 = never. If the target app comes to the front again after that,
     * it is closed again (a quick second press can restart it).
     */
    private long closedAt;
    /** Last activation of the target app (usage stats time). */
    private long lastTargetResume;
    /** Target app in front at the last poll (then don't close it). */
    private boolean targetInFront;
    /** Last visible activity per app (from usage stats), so exactly that one comes back. */
    private final Map<String, String> lastClass = new HashMap<>();

    static void arm(Context context, int key, long pressedAt) {
        long since = pressedAt;
        Boolean wasOpen = null;
        if (current != null) {
            if (current.key == key) {
                // The first press counts: the target app may be open only because of it.
                since = current.since;
                wasOpen = current.targetWasOpen;
                current.superseded = true;
            }
            current.stop();
        }
        current = new AppGuard(context.getApplicationContext(), key, pressedAt, since, wasOpen);
        current.run();
    }

    /** Ends a running guard, e.g. because the user pressed another key. */
    static void disarm(String reason) {
        AppGuard guard = current;
        if (guard != null) {
            Log.i(TAG, Keys.NAMES[guard.key] + " guard ended early: " + reason);
            guard.stop();
        }
    }

    /** Window change of the target app: check at once instead of waiting for the next poll. */
    static void onWindowChanged(String pkg) {
        AppGuard guard = current;
        if (guard != null && guard.target.equals(pkg)) {
            Log.i(TAG, pkg + " window after " + (System.currentTimeMillis() - guard.pressedAt) + " ms");
            guard.handler.removeCallbacks(guard);
            guard.run();
        }
    }

    private AppGuard(Context context, int key, long pressedAt, long since, Boolean wasOpen) {
        this.context = context;
        this.key = key;
        this.target = Keys.TARGETS[key];
        this.pressedAt = pressedAt;
        this.since = since;
        usageStats = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        pm = context.getPackageManager();
        defaultHome = findDefaultHome();
        currentSource();
        long t = System.currentTimeMillis();
        String found = findBefore(LOOKBACK_SHORT_MS);
        if (found == null) {
            found = findBefore(LOOKBACK_LONG_MS);
        }
        before = target.equals(found) ? null : found;
        targetWasOpen = wasOpen != null ? wasOpen : target.equals(found);
        String l = Keys.learned(key);
        learned = target.equals(l) ? null : l;
        learnedClass = learned != null ? Keys.learnedClass(key) : null;
        Log.i(TAG, Keys.NAMES[key] + " guard on (" + (System.currentTimeMillis() - t) + " ms), app in front: "
                + (before == null ? found + " (target itself)" : before)
                + (underneath != null ? " (below: " + underneath + ")" : "")
                + (learned != null ? ", remembered: " + learned + (learnedClass != null ? "/" + learnedClass : "") : "")
                + (targetWasOpen ? ", keeping it open" : ""));
    }

    private void stop() {
        handler.removeCallbacks(this);
        // If the guard ends first (other key, time up), close anyway.
        closeTarget("guard ending");
        Log.i(TAG, Keys.NAMES[key] + " guard off, events: " + lastTrace);
        if (Keys.remembers(key)) {
            // The previous app doesn't count: it can briefly come back after a trampoline activity.
            String app = actionApp != null && !actionApp.equals(before) ? actionApp : null;
            if (app != null && !(app.equals(learned) && eq(actionClass, learnedClass))) {
                Keys.setLearned(key, app, actionClass);
                Log.i(TAG, Keys.NAMES[key] + " key opens " + app + "/" + actionClass + " (remembered)");
            } else if (app == null && actionTransient && learned != null) {
                Keys.setLearned(key, null, null);
                Log.i(TAG, Keys.NAMES[key] + " key opens no app (remembered target cleared)");
            }
        }
        if (current == this) {
            current = null;
        }
    }

    /** App in front at the press, without invisible trampoline activities. */
    private String findBefore(long lookback) {
        String latest = null;
        String previous = null;
        try {
            UsageEvents events = usageStats.queryEvents(since - lookback, since);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED
                        && counts(event.getPackageName(), event.getClassName())) {
                    String pkg = event.getPackageName();
                    if (latest != null && !latest.equals(pkg)) {
                        previous = latest;
                    }
                    latest = pkg;
                    lastClass.put(latest, event.getClassName());
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Usage stats not readable", e);
        }
        if (latest != null) {
            underneath = previous;
        }
        return latest;
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        if (now - pressedAt > WATCH_MS) {
            stop();
            return;
        }
        long targetResumed = 0;
        boolean targetFront = false;
        String other = null;
        boolean targetSeen = false;
        // A real app came to the front after the target app (so the target app is surely below it).
        boolean otherAfterTarget = false;
        String newActionApp = null;
        String newActionClass = null;
        boolean newActionTransient = false;
        String lastResumePkg = null;
        long lastResumeAt = 0;
        StringBuilder trace = new StringBuilder();
        try {
            UsageEvents events = usageStats.queryEvents(since, now + 1000);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                if (type != UsageEvents.Event.ACTIVITY_RESUMED && type != UsageEvents.Event.ACTIVITY_PAUSED) {
                    continue;
                }
                String pkg = event.getPackageName();
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastResumePkg = pkg;
                    lastResumeAt = event.getTimeStamp();
                }
                trace.append('+').append(event.getTimeStamp() - since).append(' ')
                        .append(type == UsageEvents.Event.ACTIVITY_RESUMED ? "R " : "P ")
                        .append(pkg).append("; ");
                if (target.equals(pkg)) {
                    if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                        targetResumed = event.getTimeStamp();
                        targetFront = true;
                        targetSeen = true;
                    } else {
                        targetFront = false;
                    }
                } else if (type == UsageEvents.Event.ACTIVITY_RESUMED && counts(pkg, event.getClassName())) {
                    // A real app came to the front. If it came after the target app, the target app is gone.
                    other = pkg;
                    lastClass.put(pkg, event.getClassName());
                    targetFront = false;
                    if (targetSeen) {
                        otherAfterTarget = true;
                    }
                    if (!targetSeen) {
                        newActionApp = pkg;
                        newActionClass = event.getClassName();
                    }
                } else if (type == UsageEvents.Event.ACTIVITY_RESUMED && !targetSeen
                        && !TVQUICKACTIONS.equals(pkg) && isTransient(pkg, event.getClassName())) {
                    newActionTransient = true;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Usage stats not readable", e);
        }
        lastTrace = trace.toString();
        targetInFront = targetFront;
        if (targetResumed > lastTargetResume) {
            lastTargetResume = targetResumed;
        }
        if (targetSeen && otherAfterTarget && !targetFront && before != null) {
            targetOpened = true;
        }
        actionApp = newActionApp;
        actionClass = newActionClass;
        actionTransient = newActionTransient;
        if (targetFront && targetResumed > handledResume) {
            handledResume = targetResumed;
            String back = other != null ? other : learned != null ? learned : before;
            if (back == null) {
                Log.i(TAG, target + " in front after " + (targetResumed - since)
                        + " ms, but it was already in front before, nothing to do");
            } else {
                Log.i(TAG, target + " in front after " + (targetResumed - since)
                        + " ms, back to " + back + (other != null ? " (came after the key)"
                        : learned != null ? " (remembered)" : ""));
                if (other == null && learned != null) {
                    // Exactly the remembered target, else the app's start page (not some old page).
                    if (learnedClass != null) {
                        lastClass.put(learned, learnedClass);
                    } else {
                        lastClass.remove(learned);
                    }
                }
                // If the target is already open at the press (key pressed twice), the key closes it:
                // only the app below comes back.
                sentAway = true;
                sentAwayAt = now;
                boolean close = learned != null && learned.equals(before)
                        && (other == null || other.equals(learned));
                if (close) {
                    String below = underneath != null ? underneath : defaultHome;
                    Log.i(TAG, "Target was already open, closing it: back to " + below);
                    if (below != null) {
                        lastClass.remove(learned);
                        sendBack(below);
                    }
                } else if (other == null && learned != null && before != null && !before.equals(learned)) {
                    String base = before;
                    // First the previous app, then the target on top. Otherwise Netflix stays visible behind a
                    // transparent target (the picture menu is an overlay). Only once it is really in front:
                    // some apps start through a splash page and would cover the menu, and on HDMI the menu
                    // closed again while live TV was not yet in front.
                    Log.i(TAG, "First back to " + base + ", then the remembered target on top");
                    sendBack(base);
                    pendingBase = base;
                    pendingTarget = learned;
                    pendingSince = now;
                } else if (other == null && learned == null && revealBelow()) {
                    // Target app closed, Android shows the task below (see revealBelow).
                } else {
                    sendBack(back);
                }
            }
        }
        if (before == null && learned != null && !openedOverTarget && other == null && !targetSeen
                && now - pressedAt >= OVER_TARGET_MS) {
            openedOverTarget = true;
            Log.i(TAG, target + " was in front, opening the remembered target on top");
            if (learnedClass != null) {
                lastClass.put(learned, learnedClass);
            } else {
                lastClass.remove(learned);
            }
            sendBack(learned);
        }
        if (pendingTarget != null) {
            boolean tv = PLAYTV.equals(pendingBase);
            boolean settled = lastResumePkg != null && !target.equals(lastResumePkg)
                    && lastResumeAt >= pendingSince && now - lastResumeAt >= (tv ? SETTLE_PLAYTV_MS : SETTLE_MS);
            if (settled || now - pendingSince > (tv ? SETTLE_PLAYTV_MAX_MS : SETTLE_MAX_MS)) {
                Log.i(TAG, (settled ? lastResumePkg + " settled" : "Waited too long") + " after "
                        + (now - pendingSince) + " ms, now the remembered target");
                if (learnedClass != null) {
                    lastClass.put(pendingTarget, learnedClass);
                }
                sendBack(pendingTarget);
                pendingTarget = null;
                sentAwayAt = now;
            }
        }
        if ((sentAway ? now - sentAwayAt >= CLOSE_AFTER_MS : targetOpened)
                && pendingTarget == null) {
            closeTarget("sent away");
        }
        handler.postDelayed(this, POLL_MS);
    }

    /**
     * true if the activation counts as "app in front". Never this app's own UI.
     * tvQuickActions counts, except its start activity (MainActivity, which forwards at once).
     * Otherwise mapping the Netflix key in tvQuickActions' settings started Netflix and the
     * guard brought back the app before it instead of tvQuickActions.
     */
    private boolean counts(String pkg, String cls) {
        if (pkg == null || context.getPackageName().equals(pkg)) {
            return false;
        }
        if (TVQUICKACTIONS.equals(pkg)) {
            return cls != null && !cls.equals(TVQUICKACTIONS_TRAMPOLINE);
        }
        return !isTransient(pkg, cls) && !isStrayHome(pkg, cls);
    }

    /** Invisible activity that only triggers something and closes again at once. */
    private boolean isTransient(String pkg, String cls) {
        if (cls == null) {
            return false;
        }
        String id = pkg + "/" + cls;
        Boolean cached = TRANSIENT.get(id);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, cls), 0);
            int theme = ai.getThemeResource();
            result = theme == android.R.style.Theme_NoDisplay
                    || ((ai.flags & ActivityInfo.FLAG_NO_HISTORY) != 0
                        && (ai.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            // Unknown (e.g. alias): treat as a normal app.
        }
        TRANSIENT.put(id, result);
        return result;
    }

    /**
     * Home activity of an app that is not the selected launcher, e.g. the settings' FallbackHome,
     * which closes at once and then shows the real launcher. Doesn't count as an app.
     */
    private boolean isStrayHome(String pkg, String cls) {
        if (cls == null || pkg.equals(defaultHome)) {
            return false;
        }
        String id = pkg + "/" + cls;
        Boolean cached = STRAY_HOME.get(id);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
            for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
                if (cls.equals(ri.activityInfo.name)) {
                    result = true;
                }
            }
        } catch (RuntimeException e) {
            // Unknown: treat as a normal app.
        }
        STRAY_HOME.put(id, result);
        return result;
    }

    /**
     * Starts live TV on the current HDMI input like the source menu does (VIEW on the passthrough
     * channel). Bringing it to the front is not enough: after a short app start live TV often lost
     * its session to the input and the picture stayed black. VIEW on
     * content://android.media.tv/passthrough/…HDMI100004 brings the picture back.
     * The input comes from Settings.Global source_global_json_store_source ("hdmi1" …). Prefers the
     * CEC device on that port (as the source menu does), else the port itself (HW = port + 1 on
     * this TV). No HDMI: null, then just bring it to the front.
     */
    /** Current source; remembers HDMI, forgets it on another source. Empty = unknown. */
    private String currentSource() {
        String source = null;
        try {
            source = android.provider.Settings.Global.getString(context.getContentResolver(),
                    "source_global_json_store_source");
        } catch (RuntimeException e) {
            // Not readable: same as empty.
        }
        if (source != null && source.startsWith("hdmi")) {
            lastHdmiSource = source;
        } else if (source != null && !source.isEmpty()) {
            lastHdmiSource = null;
        }
        return source;
    }

    private Intent playTvIntent() {
        String source = currentSource();
        if (source == null || source.isEmpty()) {
            source = lastHdmiSource;
        }
        if (source == null || !source.startsWith("hdmi")) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(source.substring(4));
        } catch (NumberFormatException e) {
            return null;
        }
        String inputId = null;
        try {
            android.media.tv.TvInputManager tim =
                    (android.media.tv.TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            String hw = null;
            for (android.media.tv.TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != android.media.tv.TvInputInfo.TYPE_HDMI) {
                    continue;
                }
                // CEC device on the port: "HDMI" + physical address (e.g. 1000) + logical address (04),
                // so HDMI100004 for a device on HDMI 1. getHdmiDeviceInfo() is a system API, so it's
                // called by name.
                String id = info.getId();
                if (id.contains("/HDMI" + port + "000") && inputId == null) {
                    inputId = id;
                } else if (id.endsWith("/HW" + (port + 1))) {
                    hw = id;
                }
            }
            if (inputId == null) {
                inputId = hw;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "TV inputs not readable", e);
        }
        if (inputId == null) {
            return null;
        }
        Log.i(TAG, "Reopening " + source + " on live TV: " + inputId);
        return new Intent(Intent.ACTION_VIEW,
                android.media.tv.TvContract.buildChannelUriForPassthroughInput(inputId))
                .setComponent(new ComponentName(PLAYTV, PLAYTV + ".PlayTvActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * Closes the sent-away target app with "am force-stop" through the app's own adb access
     * (an app can't stop other apps). Only if it is not in front and "Close sent-away apps" is on.
     * Any unsaved state in the target app is lost.
     */
    private void closeTarget(String reason) {
        boolean openAgain = closedAt == 0 || lastTargetResume > closedAt;
        if (targetWasOpen || !(sentAway || targetOpened) || !openAgain || superseded || targetInFront || pendingTarget != null
                || !AppKeysHelper.closesApps()) {
            return;
        }
        closedAt = System.currentTimeMillis();
        String pkg = target;
        Log.i(TAG, "Closing " + pkg + " (" + reason + ")");
        new Thread(() -> {
            try {
                AdbClient.shell(context, "am force-stop " + pkg, 10000);
                Log.i(TAG, pkg + " closed");
            } catch (Exception e) {
                Log.w(TAG, "Could not close " + pkg + ": " + e.getMessage());
            }
        }).start();
    }

    /**
     * No user action seen: close the target app at once instead of starting the previous app.
     * Android then shows the task directly below, so whatever was started last (e.g. an overlay
     * that had not loaded yet) comes up, else the previous app. Not for live TV (the HDMI input
     * must be reopened), not without "Close sent-away apps", and not if the target app was in
     * front. If adb fails, the previous app is started as before.
     */
    private boolean revealBelow() {
        if (targetWasOpen || before == null || PLAYTV.equals(before) || !AppKeysHelper.closesApps()) {
            return false;
        }
        String pkg = target;
        String fallback = before;
        closedAt = System.currentTimeMillis();
        Log.i(TAG, "No action seen, closing " + pkg + " to show what is below");
        new Thread(() -> {
            try {
                AdbClient.shell(context, "am force-stop " + pkg, 10000);
                Log.i(TAG, pkg + " closed");
            } catch (Exception e) {
                Log.w(TAG, "Could not close " + pkg + ", bringing back " + fallback + ": " + e.getMessage());
                handler.post(() -> sendBack(fallback));
            }
        }).start();
        return true;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private String findDefaultHome() {
        try {
            ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void sendBack(String pkg) {
        if (PLAYTV.equals(pkg)) {
            Intent tv = playTvIntent();
            if (tv != null) {
                try {
                    context.startActivity(tv);
                    return;
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not reopen the TV input, bringing live TV to the front instead", e);
                }
            }
        }
        Intent intent;
        Intent exact = exactActivity(pkg);
        if (pkg.equals(defaultHome)) {
            // Only the selected launcher via HOME. Any app with a HOME activity would bring e.g.
            // FallbackHome for the system settings.
            intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
        } else if (exact != null) {
            // Exactly the page that was open before. The start activity could bring a different page.
            intent = exact;
        } else {
            intent = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (intent == null) {
                intent = pm.getLaunchIntentForPackage(pkg);
            }
        }
        if (intent == null) {
            Log.i(TAG, pkg + " has no start activity, going to the home screen");
            intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not bring back " + pkg, e);
        }
    }

    /**
     * Intent for the app's last visible activity, if it can be started from outside.
     * NEW_TASK + REORDER_TO_FRONT brings the existing activity and its task to the front instead
     * of creating a new one.
     */
    private Intent exactActivity(String pkg) {
        String cls = lastClass.get(pkg);
        if (cls == null) {
            return null;
        }
        try {
            ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, cls), 0);
            if (!ai.exported || !ai.enabled || ai.permission != null) {
                return null;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
        Log.i(TAG, "Bringing back " + pkg + "/" + cls);
        return new Intent().setComponent(new ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    }
}
