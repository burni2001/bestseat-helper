package tv.bestseat.helper;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Remote keys.
 *
 * Watches the three app keys (never consumes them). When a freed key is pressed, AppGuard is
 * armed for the app that the Philips GlobalKey service will start anyway; AppGuard sends it
 * away again. Any other key ends the guard.
 */
final class AppKeysHelper extends Helper {

    static final String TAG = "TvHelper.Keys";

    private static final Setting[] KEYS = new Setting[Keys.NAMES.length];

    static {
        for (int i = 0; i < Keys.NAMES.length; i++) {
            String app = Keys.NAMES[i];
            KEYS[i] = new Setting(Keys.PREFS[i], app + " key", true,
                    "Stop the " + app + " key from opening " + app + ", so you can map it in tvQuickActions.");
        }
    }

    /**
     * Fully close the sent-away target app. Needs adb access. Always on.
     */
    static boolean closesApps() {
        return true;
    }

    @Override
    String id() {
        return "app_keys";
    }

    @Override
    String title() {
        return "Remote keys";
    }

    @Override
    String description() {
        return Device.PHILIPS ? "Free the Prime Video, Netflix and YouTube keys and map other remote keys." : "Map remote keys to apps, actions or intents.";
    }

    @Override
    int icon() {
        return android.R.drawable.ic_dialog_dialer;
    }

    @Override
    boolean needsAccessibility() {
        return true;
    }

    @Override
    String[] permissions() {
        return new String[]{PermissionFix.USAGE};
    }

    @Override
    String missingPermission(Context context) {
        return Perms.usageAccess(context) ? null : "Usage access is missing";
    }

    @Override
    List<Setting> settings() {
        List<Setting> list = new ArrayList<>();
        // Prime, Netflix and YouTube keys with their Philips key codes only exist there.
        if (!Device.PHILIPS) {
            return list;
        }
        for (Setting s : KEYS) {
            list.add(s);
        }
        return list;
    }

    @Override
    List<Choice> choices() {
        List<Choice> list = new ArrayList<>();
        int key = Keys.NETFLIX;
        if (Device.PHILIPS) list.add(new Choice(Keys.NAMES[key] + " key target",
                () -> ActionPicker.describe(Hub.app, key),
                () -> "Choose what opens when you press the " + Keys.NAMES[key] + " key, after " + Keys.NAMES[key] + " is sent away.",
                (activity, done) -> ActionPicker.show(activity, key, done)).after(Keys.PREFS[key]));
        // The GLOBAL_FUNCTION keys, one row per key; the press types (short, double, long)
        // are in a submenu.
        for (int i = 0; i < FunctionKeys.count(); i++) {
            int fk = i;
            list.add(new Choice(FunctionKeys.name(fk) + " key",
                    () -> FunctionKeys.summary(Hub.app, fk),
                    () -> "Choose what a press, double press or long press on the " + FunctionKeys.name(fk) + " key opens.",
                    (activity, done) -> FunctionKeys.showModes(activity, fk, done)));
        }
        list.add(new Choice("Add a key…", () -> "",
                () -> "Press a remote key to add it to this list.",
                FunctionKeys::learn));
        return list;
    }

    @Override
    Status runningStatus() {
        if (!Device.PHILIPS) {
            return Status.good("Active", FunctionKeys.count() == 0 ? "Add a key to map it." : "Maps " + FunctionKeys.count() + " keys.");
        }
        List<String> freed = new ArrayList<>();
        for (int i = 0; i < Keys.NAMES.length; i++) {
            if (Keys.enabled(i)) {
                freed.add(Keys.NAMES[i]);
            }
        }
        if (freed.isEmpty()) {
            return Status.idle("All keys off", "The keys open their apps as usual.");
        }
        return Status.good("Active", "Freed: " + join(freed) + (freed.size() == 1 ? " key." : " keys."));
    }

    /** "A", "A and B", "A, B and C". */
    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(i == names.size() - 1 ? " and " : ", ");
            }
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    @Override
    void start() {
        Keys.forgetLearned();
    }

    @Override
    void stop() {
        AppGuard.disarm("helper off");
    }

    // ------------------------------------------------------------------ Accessibility service

    @Override
    int accessibilityEventTypes() {
        return AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
    }

    /** AppGuard only needs window changes of the three target apps. */
    @Override
    String[] accessibilityPackages() {
        return Keys.TARGETS;
    }

    @Override
    boolean wantsKeyEvents() {
        return true;
    }

    @Override
    void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null) {
            AppGuard.onWindowChanged(event.getPackageName().toString());
        }
    }

    @Override
    boolean consumesKey(KeyEvent event) {
        int fk = FunctionKeys.indexOf(event);
        return fk >= 0 && FunctionKeys.mapped(fk);
    }

    @Override
    void onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == FunctionKeys.KEYCODE) {
            // Log every 811 key with its scan code, to check on the TV that it arrives.
            int fk = FunctionKeys.indexOf(event);
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            Log.i(TAG, "GLOBAL_FUNCTION " + (down ? "down" : "up") + " scan " + event.getScanCode()
                    + " (" + (fk >= 0 ? FunctionKeys.name(fk) + (FunctionKeys.mapped(fk) ? ", mapped" : ", Philips default") : "unknown")
                    + ", repeat " + event.getRepeatCount() + ")");
            if (down) {
                AppGuard.disarm("key 811");
            }
            if (fk >= 0 && FunctionKeys.mapped(fk)) {
                FunctionKeys.onKey(event, fk);
            }
            return;
        }
        int key = Keys.indexOf(event);
        if (key >= 0) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            Log.i(TAG, Keys.NAMES[key] + " key " + (down ? "down" : "up")
                    + " (code " + event.getKeyCode() + ", scan " + event.getScanCode()
                    + ", repeat " + event.getRepeatCount() + ")");
            if (down && event.getRepeatCount() == 0) {
                if (Keys.enabled(key)) {
                    // Time of the press as wall clock time, like in usage stats.
                    long pressedAt = System.currentTimeMillis()
                            - (SystemClock.uptimeMillis() - event.getEventTime());
                    AppGuard.arm(Hub.app, key, pressedAt);
                } else {
                    Log.i(TAG, Keys.NAMES[key] + " key is not freed (off in settings)");
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // Any other key (OK, arrows, Back, Home …) means: the user is navigating.
            AppGuard.disarm("key " + event.getKeyCode());
        }
    }
}
