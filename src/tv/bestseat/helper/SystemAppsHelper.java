package tv.bestseat.helper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * System apps: button helper with a detail page. Turns system apps on and off that can
 * otherwise only be disabled with adb, e.g. the stock launcher, the Philips quick menu, or
 * tracking clients (Samba TV, Philips usage statistics, Google TV search).
 *
 * An app can't disable other packages (CHANGE_COMPONENT_ENABLED_STATE is a system permission).
 * So the helper connects to the TV's own adb service (127.0.0.1:5555) and runs pm as shell.
 * The first time, the TV asks "Allow debugging?" (own key in files/adbkey).
 */
final class SystemAppsHelper extends Helper {

    /**
     * Known system apps: title, packages (space separated, the first one sets the state), hint.
     * Only the ones installed on this TV are shown.
     */
    private static final String[][] KNOWN = {
            {"Google TV Launcher",
                    "com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith",
                    "Turn off Google's own launcher if you use an alternative launcher like AT4K."},
            {"Philips quick menu",
                    "org.droidtv.philipstvdrawer",
                    "Turn off the Philips Quick Menu if its entries don't work."},
            {"Samba TV",
                    "tv.samba.ssm",
                    "Turn off Samba TV so it can't recognise what's on screen."},
            {"Philips usage statistics",
                    "org.droidtv.candeebug",
                    "Turn off the Philips analytics client that reports app and channel usage."},
            {"Google TV search",
                    "com.google.android.katniss",
                    "Turn off Google's search and Assistant, which report the running app to Google."},
    };
    private static final String ADB = "adb";
    /** System apps added by the user, one per line: "package|title". */
    private static final String EXTRA = "extra";

    private static final Set<String> busy = new HashSet<>();
    private static String lastError;

    @Override
    String id() {
        return "system_apps";
    }

    @Override
    String title() {
        return "System apps";
    }

    @Override
    String description() {
        return "Turn hidden system apps on or off.";
    }

    @Override
    int icon() {
        return android.R.drawable.ic_menu_manage;
    }

    @Override
    boolean isAction() {
        return true;
    }

    @Override
    String actionHint() {
        return "Press OK to open.";
    }

    @Override
    void runAction(Context context) {
        Intent i = new Intent(context, DetailActivity.class).putExtra(DetailActivity.EXTRA_ID, id());
        if (!(context instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(i);
    }

    @Override
    Status actionStatus() {
        if (lastError != null) {
            return Status.bad("Error", lastError);
        }
        // No green "Ready" and no state list: the switches show the state.
        // Only a hint when debugging access is missing.
        return adbOk() ? Status.idle("", null) : Status.idle("Debugging access not set up", null);
    }

    @Override
    List<Choice> choices() {
        List<Choice> list = new ArrayList<>();
        for (String[] e : entries()) {
            list.add(new Choice(e[0],
                    () -> busy.contains(e[0]) ? "Working…" : state(e),
                    () -> e[2],
                    (activity, done) -> toggle(e, done)));
        }
        list.add(new Choice("Add a system app…", () -> "",
                () -> "Pick another system app to switch on or off here.",
                this::pickExtra));
        list.add(new Choice("Debugging access",
                () -> busy.contains(ADB) ? "Waiting for the TV…" : adbOk() ? "Allowed" : "Not set up",
                () -> "Set up the TV's debugging access so this app can switch system apps.",
                (activity, done) -> testAdb(done)));
        return list;
    }

    @Override
    String detailNote() {
        return "Works through the TV's network debugging.";
    }

    @Override
    void start() {
    }

    @Override
    void stop() {
    }

    // ------------------------------------------------------------------ List

    private static boolean installed(String pkg) {
        try {
            Hub.app.getPackageManager().getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Installed known apps plus the ones added by the user. */
    static List<String[]> entries() {
        List<String[]> list = new ArrayList<>();
        for (String[] e : KNOWN) {
            if (installed(e[1].split(" ")[0])) list.add(e);
        }
        String extra = Prefs.string(Hub.app, HelperRegistry.SYSTEM_APPS, EXTRA);
        if (extra != null) {
            for (String line : extra.split("\n")) {
                String[] f = line.split("\\|", 2);
                if (f.length == 2 && installed(f[0])) {
                    list.add(new String[]{f[1], f[0], "Turn " + f[1] + " on or off."});
                }
            }
        }
        return list;
    }

    /** Overlay with all other system apps; the chosen one is added to the list. */
    private void pickExtra(Activity a, Runnable done) {
        PackageManager pm = a.getPackageManager();
        java.util.Set<String> shown = new HashSet<>();
        for (String[] e : entries()) {
            shown.addAll(java.util.Arrays.asList(e[1].split(" ")));
        }
        List<String[]> items = new ArrayList<>();
        for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 || shown.contains(ai.packageName)) continue;
            items.add(new String[]{String.valueOf(ai.loadLabel(pm)), ai.packageName});
        }
        items.sort((x, y) -> x[0].compareToIgnoreCase(y[0]));
        Dialogs.pickList(a, "Add a system app", "Only turn off apps you know. Some are needed by the TV.", items, i -> {
            String line = items.get(i)[1] + "|" + items.get(i)[0];
            String extra = Prefs.string(Hub.app, this, EXTRA);
            Prefs.setString(Hub.app, this, EXTRA, extra == null ? line : extra + "\n" + line);
            a.recreate();
        });
    }

    // ------------------------------------------------------------------ State

    static boolean adbOk() {
        return Prefs.setting(Hub.app, HelperRegistry.SYSTEM_APPS, "adb_ok", false);
    }

    private static String state(String[] entry) {
        String pkg = entry[1].split(" ")[0];
        try {
            int s = Hub.app.getPackageManager().getApplicationEnabledSetting(pkg);
            return s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ? "On" : "Off";
        } catch (IllegalArgumentException e) {
            return "Not installed";
        }
    }

    // ------------------------------------------------------------------ Switching via adb

    private void toggle(String[] entry, Runnable done) {
        String title = entry[0];
        if (busy.contains(title) || busy.contains(ADB) || "Not installed".equals(state(entry))) {
            return;
        }
        boolean turnOn = !"On".equals(state(entry));
        List<String> cmds = new ArrayList<>();
        for (String pkg : entry[1].split(" ")) {
            cmds.add((turnOn ? "pm enable --user 0 " : "pm disable-user --user 0 ") + pkg);
        }
        String command = String.join("; ", cmds);
        busy.add(title);
        done.run();
        Hub.log("System apps: " + command);
        new Thread(() -> {
            String output;
            String error = null;
            try {
                output = AdbClient.shell(Hub.app, command, 60000);
            } catch (Exception e) {
                output = null;
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Log.w(Hub.TAG, "System apps: adb failed", e);
            }
            String out = output;
            String err = error;
            Hub.handler.post(() -> {
                busy.remove(title);
                boolean ok = turnOn == "On".equals(state(entry));
                if (ok) {
                    lastError = null;
                    Prefs.setSetting(Hub.app, this, "adb_ok", true);
                    Hub.log("System apps: " + title + " " + (turnOn ? "on" : "off") + " (" + out + ")");
                    Toast.makeText(Hub.app, title + (turnOn ? " turned on" : " turned off"), Toast.LENGTH_SHORT).show();
                } else {
                    lastError = err != null ? err : "Could not change " + title + (out == null || out.isEmpty() ? "" : ": " + out);
                    if (err != null) {
                        Prefs.setSetting(Hub.app, this, "adb_ok", false);
                    }
                    Hub.log("System apps: failed: " + lastError);
                    Toast.makeText(Hub.app, lastError, Toast.LENGTH_LONG).show();
                }
                done.run();
            });
        }).start();
    }

    private void testAdb(Runnable done) {
        if (busy.contains(ADB)) {
            return;
        }
        busy.add(ADB);
        done.run();
        new Thread(() -> {
            String error = null;
            try {
                String out = AdbClient.shell(Hub.app, "echo ok", 60000);
                if (!"ok".equals(out)) {
                    error = "Unexpected answer: " + out;
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String err = error;
            Hub.handler.post(() -> {
                busy.remove(ADB);
                Prefs.setSetting(Hub.app, this, "adb_ok", err == null);
                lastError = err;
                Hub.log("System apps: debugging access " + (err == null ? "works" : "failed: " + err));
                Toast.makeText(Hub.app, err == null ? "Debugging access works" : err, Toast.LENGTH_LONG).show();
                done.run();
            });
        }).start();
    }
}
