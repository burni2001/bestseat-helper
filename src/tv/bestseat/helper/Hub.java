package tv.bestseat.helper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Central hub in the app process (everything on the main thread): starts and stops the helpers
 * to match the settings, manages the foreground service and the accessibility service, and
 * passes their events to the running helpers.
 *
 * Errors of one helper are caught so the others keep running.
 */
final class Hub {

    static final String TAG = "TvHelper";

    static Context app;
    static Handler handler;

    private static final Set<Helper> running = new LinkedHashSet<>();
    private static final Map<Helper, String> failures = new HashMap<>();

    private static HostAccessibilityService accessibility;
    private static boolean serviceRunning;
    private static ScreenReceiver screenReceiver;
    private static boolean screenOn = true;

    private Hub() {
    }

    static void init(Context context) {
        if (app == null) {
            app = context.getApplicationContext();
            handler = new Handler(Looper.getMainLooper());
            screenOn = ((PowerManager) app.getSystemService(Context.POWER_SERVICE)).isInteractive();
        }
    }

    static void log(String msg) {
        Log.i(TAG, msg);
    }

    // ------------------------------------------------------------------ Sync

    /** Syncs the running helpers with the settings. Safe to call any number of times. */
    static void sync(Context context) {
        init(context);
        boolean needService = false;
        for (Helper h : HelperRegistry.ALL) {
            if (h.isAction()) {
                continue;
            }
            boolean should = shouldRun(h);
            if (should && !running.contains(h)) {
                try {
                    h.start();
                    running.add(h);
                    failures.remove(h);
                    log(h.id() + " started");
                } catch (RuntimeException e) {
                    Log.w(TAG, h.id() + " failed to start", e);
                    failures.put(h, String.valueOf(e.getMessage()));
                    safeStop(h);
                }
            } else if (!should && running.contains(h)) {
                running.remove(h);
                safeStop(h);
                log(h.id() + " stopped");
            }
            try {
                h.applyComponents(app, running.contains(h));
            } catch (RuntimeException e) {
                Log.w(TAG, h.id() + ": components", e);
            }
            if (running.contains(h) && h.needsService()) {
                needService = true;
            }
        }
        updateScreenReceiver();
        if (needService && !serviceRunning) {
            HostService.start(app);
        } else if (!needService && serviceRunning) {
            app.stopService(new Intent(app, HostService.class));
        }
        updateAccessibility();
    }

    private static boolean shouldRun(Helper h) {
        return Prefs.enabled(app, h)
                && !homeProvides(h)
                && (!h.needsAccessibility() || accessibility != null);
    }

    private static void safeStop(Helper h) {
        try {
            h.stop();
        } catch (RuntimeException e) {
            Log.w(TAG, h.id() + " failed to stop", e);
        }
    }

    static final String HOME_LAUNCHER = "tv.bestseat.launcher";

    /** Is the Best Seat launcher the home screen? */
    static boolean launcherIsHome() {
        try {
            android.content.pm.ResolveInfo ri = app.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && HOME_LAUNCHER.equals(ri.activityInfo.packageName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Helper the launcher has taken over: hidden and stopped. */
    static boolean homeProvides(Helper h) {
        return h.providedByHome() && launcherIsHome();
    }

    static boolean isRunning(Helper h) {
        return running.contains(h);
    }

    /** State for the UI. */
    static Status status(Helper h) {
        if (h.isAction()) {
            return h.actionStatus();
        }
        if (!Prefs.enabled(app, h)) {
            return Status.idle("Off", null);
        }
        if (h.needsAccessibility() && accessibility == null) {
            return Status.bad("Accessibility service is off",
                    "Turn on Best Seat Helper under Settings › Accessibility.");
        }
        String failure = failures.get(h);
        if (failure != null) {
            return Status.bad("Error", failure);
        }
        String missing = h.missingPermission(app);
        if (missing != null) {
            return Status.bad(missing, "See Permissions on the main page.");
        }
        return h.runningStatus();
    }

    // ------------------------------------------------------------------ Foreground service

    static void onServiceCreated() {
        serviceRunning = true;
    }

    static void onServiceDestroyed() {
        serviceRunning = false;
    }

    /** Is the service still needed? (e.g. after a process restart through START_STICKY) */
    static boolean serviceNeeded() {
        for (Helper h : running) {
            if (h.needsService()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Screen

    static boolean screenOn() {
        return screenOn;
    }

    private static void updateScreenReceiver() {
        if (!running.isEmpty() && screenReceiver == null) {
            screenReceiver = new ScreenReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            app.registerReceiver(screenReceiver, filter);
            screenOn = ((PowerManager) app.getSystemService(Context.POWER_SERVICE)).isInteractive();
        } else if (running.isEmpty() && screenReceiver != null) {
            app.unregisterReceiver(screenReceiver);
            screenReceiver = null;
        }
    }

    static void onScreenChanged(boolean on) {
        screenOn = on;
        for (Helper h : running.toArray(new Helper[0])) {
            try {
                h.onScreenChanged(on);
            } catch (RuntimeException e) {
                Log.w(TAG, h.id() + ": screen", e);
            }
        }
    }

    // ------------------------------------------------------------------ Accessibility service

    static HostAccessibilityService accessibility() {
        return accessibility;
    }

    static void onAccessibilityConnected(HostAccessibilityService service) {
        if (service != null) {
            init(service);
        }
        if (app == null) {
            return;
        }
        accessibility = service;
        log("Accessibility service " + (service != null ? "connected" : "disconnected"));
        sync(app);
        for (Helper h : running.toArray(new Helper[0])) {
            try {
                h.onAccessibilityConnected(service != null);
            } catch (RuntimeException e) {
                Log.w(TAG, h.id() + ": accessibility", e);
            }
        }
    }

    /**
     * Limits event types, packages and the key filter to what running helpers need.
     * If none runs, the accessibility service only watches this app (practically no load).
     */
    static void updateAccessibility() {
        HostAccessibilityService service = accessibility;
        if (service == null) {
            return;
        }
        int types = 0;
        boolean keys = false;
        Set<String> packages = new LinkedHashSet<>();
        for (Helper h : running) {
            types |= h.accessibilityEventTypes();
            keys |= h.wantsKeyEvents();
            for (String p : h.accessibilityPackages()) {
                packages.add(p);
            }
        }
        if (types == 0 || packages.isEmpty()) {
            types = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            packages.clear();
            packages.add(app.getPackageName());
        }
        try {
            AccessibilityServiceInfo info = service.getServiceInfo();
            if (info == null) {
                return;
            }
            info.eventTypes = types;
            info.packageNames = packages.toArray(new String[0]);
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 40;
            keys |= capture != null;
            info.flags = keys ? AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS : 0;
            service.setServiceInfo(info);
            log("Accessibility: packages " + packages + ", key filter " + keys);
        } catch (RuntimeException e) {
            Log.w(TAG, "setServiceInfo failed", e);
        }
    }

    static void onAccessibilityEvent(AccessibilityEvent event) {
        for (Helper h : running.toArray(new Helper[0])) {
            if ((h.accessibilityEventTypes() & event.getEventType()) == 0) {
                continue;
            }
            try {
                h.onAccessibilityEvent(event);
            } catch (RuntimeException e) {
                Log.w(TAG, h.id() + ": accessibility event", e);
            }
        }
    }

    /** Receiver for "Add a key": gets the next key instead of the helpers. */
    private static volatile KeyCapture capture;

    /** Sets or clears the receiver and switches the key filter of the accessibility service for it. */
    static void setCapture(KeyCapture c) {
        capture = c;
        updateAccessibility();
    }

    static KeyCapture capture() {
        return capture;
    }
    /** Key code whose key up is still consumed after learning. */
    private static int capturedCode = -1;

    static boolean onKeyEvent(KeyEvent event) {
        KeyCapture c = capture;
        if (event.getKeyCode() == capturedCode && event.getAction() == KeyEvent.ACTION_UP) {
            capturedCode = -1;
            return true;
        }
        if (c != null && capturedCode == -1 && !FunctionKeys.isNavigation(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                capturedCode = event.getKeyCode();
                KeyEvent copy = new KeyEvent(event);
                handler.post(() -> {
                    setCapture(null);
                    c.onKey(copy);
                });
            }
            return true;
        }
        boolean consumed = false;
        for (Helper h : running.toArray(new Helper[0])) {
            if (!h.wantsKeyEvents()) {
                continue;
            }
            try {
                h.onKeyEvent(event);
                consumed |= h.consumesKey(event);
            } catch (RuntimeException e) {
                Log.w(TAG, h.id() + ": key event", e);
            }
        }
        return consumed;
    }
}
