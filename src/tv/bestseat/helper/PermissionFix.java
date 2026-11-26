package tv.bestseat.helper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;

/**
 * Grants a missing permission with one press: opens the TV's settings screen for it if the TV
 * has one, otherwise runs the matching command through the TV's own debugging access
 * (AdbClient, asks "Allow debugging?" the first time).
 */
final class PermissionFix {

    static final String ACCESSIBILITY = "accessibility";
    static final String USAGE = "usage";
    static final String OVERLAY = "overlay";
    static final String BATTERY = "battery";
    static final String WRITE_SETTINGS = "write_settings";
    static final String SECURE = "secure";
    static final String DEBUGGING = "debugging";

    /** Permissions that are being granted right now. */
    private static final Set<String> busy = new HashSet<>();

    private PermissionFix() {
    }

    static boolean granted(Context c, String id) {
        switch (id) {
            case ACCESSIBILITY:
                return Hub.accessibility() != null;
            case USAGE:
                return Perms.usageAccess(c);
            case OVERLAY:
                return Perms.overlay(c);
            case BATTERY:
                return Perms.batteryExempt(c);
            case WRITE_SETTINGS:
                return Settings.System.canWrite(c);
            case SECURE:
                return Perms.writeSecure(c);
            case DEBUGGING:
                return SystemAppsHelper.adbOk();
            default:
                return true;
        }
    }

    static String title(String id) {
        switch (id) {
            case ACCESSIBILITY:
                return "Accessibility service";
            case USAGE:
                return "Usage access";
            case OVERLAY:
                return "Display over other apps";
            case BATTERY:
                return "No battery restrictions";
            case WRITE_SETTINGS:
                return "Change settings";
            case SECURE:
                return "Secure settings";
            default:
                return "Debugging access";
        }
    }

    /** First missing permission of the helper, or null. */
    static String missing(Context c, Helper h) {
        if (h.needsAccessibility() && !granted(c, ACCESSIBILITY)) {
            return ACCESSIBILITY;
        }
        for (String id : h.permissions()) {
            if (!granted(c, id)) {
                return id;
            }
        }
        return null;
    }

    static boolean busy(String id) {
        return busy.contains(id);
    }

    /** Settings screen for the permission, or null if this TV has none. */
    private static Intent settingsIntent(Context c, String id) {
        String action;
        switch (id) {
            case ACCESSIBILITY:
                action = Settings.ACTION_ACCESSIBILITY_SETTINGS;
                break;
            case USAGE:
                action = Settings.ACTION_USAGE_ACCESS_SETTINGS;
                break;
            default:
                return null;
        }
        Intent i = new Intent(action);
        ResolveInfo ri = c.getPackageManager().resolveActivity(i, 0);
        // Some TVs map missing screens to an empty stub activity.
        if (ri == null || ri.activityInfo == null || ri.activityInfo.name.endsWith("EmptyStubActivity")) {
            return null;
        }
        return i;
    }

    /** Shell command that grants the permission, or null. */
    private static String command(Context c, String id) {
        String pkg = c.getPackageName();
        switch (id) {
            case USAGE:
                return "appops set " + pkg + " GET_USAGE_STATS allow";
            case OVERLAY:
                return "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow";
            case BATTERY:
                return "cmd deviceidle whitelist +" + pkg;
            case WRITE_SETTINGS:
                return "appops set " + pkg + " WRITE_SETTINGS allow";
            case SECURE:
                return "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS";
            case DEBUGGING:
                return "echo ok";
            default:
                return null;
        }
    }

    /** Hint for a missing permission: what pressing OK does. */
    static String hint(Context c, String id) {
        if (settingsIntent(c, id) != null) {
            return id.equals(ACCESSIBILITY)
                    ? "Press OK to open the settings, then turn on Best Seat Helper."
                    : "Press OK to open the settings, then allow Best Seat Helper.";
        }
        return id.equals(DEBUGGING)
                ? "Press OK, then choose “Always allow” on the TV."
                : "Press OK to allow it. The first time, choose “Always allow” on the TV.";
    }

    /** Opens the settings screen or grants via debugging; done runs on the main thread when finished. */
    static void fix(Activity a, String id, Runnable done) {
        Intent settings = settingsIntent(a, id);
        if (settings != null) {
            try {
                a.startActivity(settings);
                return;
            } catch (RuntimeException e) {
                Log.w(Hub.TAG, "Permission: settings screen failed", e);
            }
        }
        String cmd = command(a, id);
        if (cmd == null || busy.contains(id)) {
            return;
        }
        busy.add(id);
        done.run();
        Context app = a.getApplicationContext();
        new Thread(() -> {
            String error = null;
            try {
                String out = AdbClient.shell(app, cmd, 60000);
                if (id.equals(DEBUGGING) && !"ok".equals(out)) {
                    error = "Unexpected answer: " + out;
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Log.w(Hub.TAG, "Permission: adb failed", e);
            }
            String err = error;
            Hub.handler.post(() -> {
                busy.remove(id);
                Prefs.setSetting(app, HelperRegistry.SYSTEM_APPS, "adb_ok", err == null);
                Hub.log("Permission " + id + (err == null ? " granted" : " failed: " + err));
                Toast.makeText(app, err == null ? "Permission granted" : err, Toast.LENGTH_LONG).show();
                Hub.sync(app);
                done.run();
            });
        }).start();
    }
}
