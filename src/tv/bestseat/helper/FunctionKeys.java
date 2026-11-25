package tv.bestseat.helper;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Remote keys that report the generic Android key code 811 (GLOBAL_FUNCTION) on Philips TVs,
 * plus keys learned on any TV. Each key can be mapped for a short, double and long press.
 *
 * Other key apps only see 811 and can map these keys only together. They differ only in the
 * scan code (measured with getevent).
 *
 * Stored per key and press type as "fn_<id>", "fn_<id>_double", "fn_<id>_long":
 * null = system default, "none" = do nothing, "intent:<action>", "app:<package>",
 * "activity:<action | package/class | class>", "broadcast:<action>".
 * If any press type is mapped, the key is consumed completely; unmapped types then do nothing.
 * A short press fires at once on key down as long as neither double nor long is mapped.
 * Otherwise it fires on key up (no double) or DOUBLE_MS later if no second press comes.
 */
final class FunctionKeys {

    static final int KEYCODE = 811;
    static final String TAG = AppKeysHelper.TAG;

    /**
     * Philips keys {id, name, key code, scan code, long press possible}. The coloured Assistant
     * key arrives as 811 with scan code 217 (BLE remote, HID 0x0c0221). Quick Menu sends no long
     * press; for Assistant, down and up arrive at the same time.
     */
    private static final String[][] PHILIPS = {
            {"sources", "Sources", "811", "768", "1"},
            {"tv", "TV", "811", "767", "1"},
            {"quick_menu", "Quick Menu", "811", "765", "0"},
            {"text", "Text", "811", "369", "1"},
            {"settings", "Settings", "811", "141", "1"},
            {"assistant", "Assistant", "811", "217", "0"},
    };
    /** Learned keys, one per line: "id|name|key code|scan code|1". */
    private static final String LEARNED = "learned_keys";
    private static List<String[]> keys;

    static final int SINGLE = 0;
    static final int DOUBLE = 1;
    static final int LONG = 2;
    static final String[] MODE_NAMES = {"press", "double press", "long press"};
    private static final String[] MODE_SUFFIX = {"", "_double", "_long"};

    /** From here on the press counts as long. */
    static final long LONG_MS = 500;
    /** How long to wait for a second press after key up. */
    static final long DOUBLE_MS = 350;

    private static final String NONE = "none";
    private static final String INTENT = "intent:";
    private static final String APP = "app:";
    private static final String ACTIVITY = "activity:";
    private static final String BROADCAST = "broadcast:";

    /** Fixed targets: {title, action}. */
    private static final String[][] ACTIONS = {
            {"Control Center", "tv.bestseat.launcher.CONTROL_CENTER"},
            {"Screensaver", "tv.bestseat.launcher.SCREENSAVER"},
            {"App Switcher", "tv.bestseat.launcher.SWITCHER"},
            {"Last app", "tv.bestseat.launcher.LAST_APP"},
            {"All Apps", "tv.bestseat.launcher.ALL_APPS"},
            {"Sources panel", "org.droidtv.intent.action.SOURCE_LIST"},
            {"Picture settings", "tv.bestseat.launcher.PICTURE"},
            {"Ambilight style", "android.settings.AMBILIGHT_STYLE"},
            {"TV settings", "android.settings.SETTINGS"},
            {"Restart dialog", "org.droidtv.intent.action.CONFIRM_REBOOT"},
    };

    // State of the current press (Hub.handler thread only).
    private static int downKey = -1;
    private static boolean handled;
    private static int pendingKey = -1;
    private static final Runnable longPress = FunctionKeys::onLongTimeout;
    private static final Runnable singleLater = FunctionKeys::onDoubleTimeout;

    private FunctionKeys() {
    }

    /** All mappable keys: the fixed table on Philips TVs, plus the learned ones. */
    static List<String[]> keys() {
        if (keys == null) {
            List<String[]> list = new ArrayList<>();
            if (Device.PHILIPS) {
                Collections.addAll(list, PHILIPS);
            }
            String saved = Prefs.string(Hub.app, HelperRegistry.APP_KEYS, LEARNED);
            if (saved != null) {
                for (String line : saved.split("\n")) {
                    String[] f = line.split("\\|");
                    if (f.length == 5) list.add(f);
                }
            }
            keys = list;
        }
        return keys;
    }

    static int count() {
        return keys().size();
    }

    static String name(int key) {
        return keys().get(key)[1];
    }

    private static String id(int key) {
        return keys().get(key)[0];
    }

    static boolean isLearned(int key) {
        return id(key).startsWith("k");
    }

    /** Index of the key or -1. A stored scan code must match; 0 means any. */
    static int indexOf(KeyEvent event) {
        List<String[]> list = keys();
        for (int i = 0; i < list.size(); i++) {
            String[] k = list.get(i);
            int scan = Integer.parseInt(k[3]);
            if (event.getKeyCode() == Integer.parseInt(k[2]) && (scan == 0 || scan == event.getScanCode())) {
                return i;
            }
        }
        return -1;
    }

    static boolean hasMode(int key, int mode) {
        return mode != LONG || "1".equals(keys().get(key)[4]);
    }

    static String value(int key, int mode) {
        if (!hasMode(key, mode)) {
            return null;
        }
        return Prefs.string(Hub.app, HelperRegistry.APP_KEYS, "fn_" + id(key) + MODE_SUFFIX[mode]);
    }

    private static void set(int key, int mode, String value) {
        Prefs.setString(Hub.app, HelperRegistry.APP_KEYS, "fn_" + id(key) + MODE_SUFFIX[mode], value);
    }

    static boolean mapped(int key, int mode) {
        return value(key, mode) != null;
    }

    /** Is the key consumed? */
    static boolean mapped(int key) {
        return mapped(key, SINGLE) || mapped(key, DOUBLE) || mapped(key, LONG);
    }

    /** Short text for the row on the detail page. */
    static String describe(Context c, int key, int mode) {
        String v = value(key, mode);
        if (v == null) {
            // If another press type is mapped, the key is consumed: then nothing happens here.
            return mapped(key) ? "Do nothing" : "System default";
        }
        if (NONE.equals(v)) {
            return "Do nothing";
        }
        if (v.startsWith(INTENT)) {
            String action = v.substring(INTENT.length());
            for (String[] a : ACTIONS) {
                if (a[1].equals(action)) {
                    return a[0];
                }
            }
            return action;
        }
        if (v.startsWith(APP)) {
            return ActionPicker.label(c, v.substring(APP.length()));
        }
        if (v.startsWith(ACTIVITY)) {
            return "Activity · " + shortName(v.substring(ACTIVITY.length()));
        }
        if (v.startsWith(BROADCAST)) {
            return "Broadcast · " + shortName(v.substring(BROADCAST.length()));
        }
        return v;
    }

    // ------------------------------------------------------------------ Keys

    /** Mapped key: down, repeat and up. */
    static void onKey(KeyEvent event, int key) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down) {
            if (event.getRepeatCount() > 0) {
                return;
            }
            Hub.handler.removeCallbacks(longPress);
            downKey = key;
            handled = false;
            if (pendingKey == key) {
                // Second press within DOUBLE_MS: double press.
                Hub.handler.removeCallbacks(singleLater);
                pendingKey = -1;
                handled = true;
                run(key, DOUBLE);
                return;
            }
            if (pendingKey >= 0) {
                // Another key pressed: run the pending single press at once.
                Hub.handler.removeCallbacks(singleLater);
                int k = pendingKey;
                pendingKey = -1;
                run(k, SINGLE);
            }
            if (mapped(key, LONG)) {
                Hub.handler.postDelayed(longPress, LONG_MS);
            }
            if (!mapped(key, DOUBLE) && !mapped(key, LONG)) {
                handled = true;
                run(key, SINGLE);
            }
        } else {
            Hub.handler.removeCallbacks(longPress);
            if (downKey != key || handled) {
                downKey = -1;
                return;
            }
            downKey = -1;
            if (mapped(key, DOUBLE)) {
                pendingKey = key;
                Hub.handler.postDelayed(singleLater, DOUBLE_MS);
            } else {
                run(key, SINGLE);
            }
        }
    }

    private static void onLongTimeout() {
        if (downKey >= 0 && !handled) {
            handled = true;
            run(downKey, LONG);
        }
    }

    private static void onDoubleTimeout() {
        int k = pendingKey;
        pendingKey = -1;
        if (k >= 0) {
            run(k, SINGLE);
        }
    }

    /** Runs a press type. */
    static void run(int key, int mode) {
        String v = value(key, mode);
        String what = name(key) + " key " + MODE_NAMES[mode];
        if (v == null || NONE.equals(v)) {
            Log.i(TAG, what + ": nothing set");
            return;
        }
        Intent intent = null;
        if (v.startsWith(INTENT)) {
            String action = v.substring(INTENT.length());
            intent = new Intent(action);
            // Always open Android settings fresh, not at the old page.
            if (action.startsWith("android.settings.")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            }
        } else if (v.startsWith(APP)) {
            String pkg = v.substring(APP.length());
            intent = Hub.app.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (intent == null) {
                intent = Hub.app.getPackageManager().getLaunchIntentForPackage(pkg);
            }
        }
        if (v.startsWith(BROADCAST)) {
            sendBroadcast(v.substring(BROADCAST.length()));
            Log.i(TAG, what + ": sent " + v);
            return;
        }
        if (v.startsWith(ACTIVITY)) {
            intent = activityIntent(Hub.app, v.substring(ACTIVITY.length()));
        }
        if (intent == null) {
            Log.w(TAG, what + ": nothing to open for " + v);
            return;
        }
        try {
            Hub.app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Log.i(TAG, what + ": opened " + v);
        } catch (RuntimeException e) {
            Log.w(TAG, what + ": could not open " + v, e);
        }
    }

    // ------------------------------------------------------------------ Choice

    /** Last part after a dot or slash, so the row stays short. */
    private static String shortName(String spec) {
        int i = Math.max(spec.lastIndexOf('.'), spec.lastIndexOf('/'));
        return i >= 0 && i < spec.length() - 1 ? spec.substring(i + 1) : spec;
    }

    /**
     * Activity intent from free input: "package/class", an action that opens an activity,
     * or a full class name (the package is the longest installed prefix of it).
     */
    static Intent activityIntent(Context c, String spec) {
        PackageManager pm = c.getPackageManager();
        if (spec.contains("/")) {
            ComponentName cn = ComponentName.unflattenFromString(spec);
            return cn == null ? null : new Intent().setComponent(cn);
        }
        Intent byAction = new Intent(spec);
        if (pm.resolveActivity(byAction, 0) != null) {
            return byAction;
        }
        String pkg = spec;
        while (pkg.contains(".")) {
            pkg = pkg.substring(0, pkg.lastIndexOf('.'));
            try {
                pm.getActivityInfo(new ComponentName(pkg, spec), 0);
                return new Intent().setComponent(new ComponentName(pkg, spec));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /**
     * Broadcast: implicit (reaches receivers registered at runtime, e.g. the system's game bar),
     * plus explicit to every manifest receiver, because implicit broadcasts don't reach those
     * since Android 8.
     */
    private static void sendBroadcast(String action) {
        Hub.app.sendBroadcast(new Intent(action));
        for (ResolveInfo ri : Hub.app.getPackageManager().queryBroadcastReceivers(new Intent(action), 0)) {
            Hub.app.sendBroadcast(new Intent(action).setComponent(
                    new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)));
        }
    }

    /** Free input of an intent: type (activity/broadcast) and action or class. */
    private static void enterIntent(Activity a, int key, int mode, Runnable done) {
        String cur = value(key, mode);
        boolean[] broadcast = {cur != null && cur.startsWith(BROADCAST)};
        LinearLayout panel = Dialogs.panel(a);
        Dialog dialog = Dialogs.show(a, panel);
        panel.addView(Ui.text(a, "Enter intent", 26, Ui.TEXT, true));
        TextView sub = Ui.text(a, "Enter an action, package/class or full class name.", 14, Ui.TEXT_DIM, false);
        sub.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 14));
        panel.addView(sub);

        LinearLayout type = Ui.row(a);
        type.setMinimumHeight(Ui.dp(a, 48));
        type.addView(Ui.text(a, "Type", 17, Ui.TEXT, true), Ui.fill());
        TextView typeValue = Ui.text(a, broadcast[0] ? "Broadcast" : "Activity", 15, Ui.TEXT_DIM, false);
        type.addView(typeValue);
        type.setOnClickListener(v -> {
            broadcast[0] = !broadcast[0];
            typeValue.setText(broadcast[0] ? "Broadcast" : "Activity");
        });
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        typeParams.bottomMargin = Ui.dp(a, 10);
        panel.addView(type, typeParams);

        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.TEXT_DIM);
        input.setHint("org.example.ACTION or com.example.app/.SomeActivity");
        input.setTextSize(16);
        input.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.ROW);
        bg.setCornerRadius(Ui.dp(a, 12));
        input.setBackground(bg);
        if (cur != null && (cur.startsWith(ACTIVITY) || cur.startsWith(BROADCAST))) {
            input.setText(cur.substring(cur.indexOf(':') + 1));
            input.setSelection(input.getText().length());
        }
        panel.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView error = Ui.text(a, "", 14, Status.BAD, false);
        error.setMaxLines(2);
        error.setPadding(0, Ui.dp(a, 8), 0, 0);
        panel.addView(error);

        LinearLayout save = Ui.row(a);
        save.setGravity(android.view.Gravity.CENTER);
        save.setMinimumHeight(Ui.dp(a, 48));
        save.addView(Ui.text(a, "Save", 16, Ui.TEXT, true));
        save.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                error.setText("Enter an action or class.");
                return;
            }
            if (!broadcast[0] && activityIntent(a, text) == null) {
                error.setText("No activity found for this. Check the spelling or choose Broadcast.");
                return;
            }
            set(key, mode, (broadcast[0] ? BROADCAST : ACTIVITY) + text);
            dialog.dismiss();
            done.run();
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                save.requestFocus();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = Ui.dp(a, 16);
        panel.addView(save, saveParams);
        dialog.show();
        type.requestFocus();
    }

    /** Summary for the single row per key. */
    static String summary(Context c, int key) {
        if (!mapped(key)) {
            return "System default";
        }
        List<String> parts = new ArrayList<>();
        String[] prefix = {"", "Double: ", "Long: "};
        for (int m = 0; m < MODE_NAMES.length; m++) {
            if (mapped(key, m)) {
                parts.add(prefix[m] + describe(c, key, m));
            }
        }
        return String.join(" · ", parts);
    }

    /** Submenu of a key: one row per press type with its current target. */
    static void showModes(Activity a, int key, Runnable done) {
        LinearLayout panel = Dialogs.panel(a);
        Dialog dialog = Dialogs.show(a, panel);
        panel.addView(Ui.text(a, name(key) + " key", 26, Ui.TEXT, true));
        TextView sub = Ui.text(a, "Choose a press type to set what it opens.", 14, Ui.TEXT_DIM, false);
        sub.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 14));
        panel.addView(sub);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4));
        panel.addView(list);
        View first = null;
        for (int m = 0; m < MODE_NAMES.length; m++) {
            if (!hasMode(key, m)) {
                continue;
            }
            int mode = m;
            String title = MODE_NAMES[m].substring(0, 1).toUpperCase() + MODE_NAMES[m].substring(1);
            View row = ActionPicker.item(a, list, title, describe(a, key, m), false, () -> {
                dialog.dismiss();
                // After the choice, back to the submenu with the new values.
                show(a, key, mode, () -> {
                    done.run();
                    showModes(a, key, done);
                });
            });
            if (first == null) {
                first = row;
            }
        }
        if (isLearned(key)) {
            ActionPicker.item(a, list, "Remove this key", "", false, () -> {
                dialog.dismiss();
                remove(key);
                a.recreate();
            });
        }
        if (!hasMode(key, LONG)) {
            TextView note = Ui.text(a, "This key has no long press.", 13, Ui.TEXT_DIM, false);
            note.setPadding(Ui.dp(a, 8), Ui.dp(a, 6), 0, 0);
            panel.addView(note);
        }
        dialog.setOnDismissListener(d -> done.run());
        dialog.show();
        first.requestFocus();
    }

    static void show(Activity a, int key, int mode, Runnable done) {
        LinearLayout panel = Dialogs.panel(a);
        Dialog dialog = Dialogs.show(a, panel);
        panel.addView(Ui.text(a, name(key) + " key, " + MODE_NAMES[mode], 26, Ui.TEXT, true));
        TextView sub = Ui.text(a, "Choose what a " + MODE_NAMES[mode] + " on the " + name(key) + " key opens. "
                + "If another key app maps this key too, remove it there.", 14, Ui.TEXT_DIM, false);
        sub.setMaxLines(3);
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

        String cur = value(key, mode);
        List<View> rows = new ArrayList<>();
        rows.add(pick(a, list, dialog, done, key, mode, "System default", "As without the helper", null, cur));
        rows.add(pick(a, list, dialog, done, key, mode, "Do nothing", "", NONE, cur));
        boolean custom = cur != null && (cur.startsWith(ACTIVITY) || cur.startsWith(BROADCAST));
        rows.add(ActionPicker.item(a, list, "Enter intent…", custom ? describe(a, key, mode) : "Activity or broadcast",
                custom, () -> {
                    dialog.dismiss();
                    enterIntent(a, key, mode, done);
                }));
        for (String[] act : ACTIONS) {
            // Only targets that exist on this TV.
            if (!available(a, act[1])) continue;
            rows.add(pick(a, list, dialog, done, key, mode, act[0], "", INTENT + act[1], cur));
        }
        for (String[] app : ActionPicker.apps(a)) {
            rows.add(pick(a, list, dialog, done, key, mode, app[0], "", APP + app[1], cur));
        }
        dialog.show();
        View focus = rows.get(0);
        for (View r : rows) {
            if (Boolean.TRUE.equals(r.getTag())) {
                focus = r;
            }
        }
        focus.requestFocus();
    }

    private static View pick(Activity a, LinearLayout list, Dialog dialog, Runnable done, int key, int mode,
                             String title, String detail, String value, String cur) {
        boolean current = value == null ? cur == null : value.equals(cur);
        return ActionPicker.item(a, list, title, detail, current, () -> {
            set(key, mode, value);
            dialog.dismiss();
            done.run();
        });
    }

    private static boolean available(Context c, String action) {
        return new Intent(action).resolveActivity(c.getPackageManager()) != null;
    }

    // ------------------------------------------------------------------ Learning keys

    /** These keys stay free for navigation and can't be learned. */
    static boolean isNavigation(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME:
                return true;
            default:
                return false;
        }
    }

    /**
     * "Add a key" overlay: the next key arrives through the accessibility service (Hub.capture),
     * or through the overlay itself as a fallback. Known keys open their submenu at once.
     */
    static void learn(Activity a, Runnable done) {
        LinearLayout panel = Dialogs.panel(a);
        Dialog dialog = Dialogs.show(a, panel);
        panel.addView(Ui.text(a, "Add a key", 26, Ui.TEXT, true));
        TextView sub = Ui.text(a, "Press the remote key you want to map. Press Back to cancel.", 16, Ui.TEXT_DIM, false);
        sub.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 8));
        panel.addView(sub);
        boolean[] got = {false};
        KeyCapture take = event -> {
            if (got[0]) return;
            got[0] = true;
            dialog.dismiss();
            int key = indexOf(event);
            if (key >= 0) {
                showModes(a, key, done);
            } else {
                add(event);
                // New row in the list: rebuild the page.
                a.recreate();
            }
        };
        Hub.setCapture(take);
        dialog.setOnDismissListener(d -> {
            if (Hub.capture() == take) Hub.setCapture(null);
        });
        dialog.setOnKeyListener((d, code, event) -> {
            if (isNavigation(code)) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) take.onKey(event);
            return true;
        });
        dialog.show();
    }

    private static int add(KeyEvent event) {
        int code = event.getKeyCode();
        int scan = event.getScanCode();
        String name = code == 811 ? "Function key " + scan : label(code);
        String line = "k" + code + "_" + scan + "|" + name + "|" + code + "|" + scan + "|1";
        String saved = Prefs.string(Hub.app, HelperRegistry.APP_KEYS, LEARNED);
        Prefs.setString(Hub.app, HelperRegistry.APP_KEYS, LEARNED, saved == null ? line : saved + "\n" + line);
        keys = null;
        resetPress();
        Log.i(TAG, "Learned key " + name + " (code " + code + ", scan " + scan + ")");
        return count() - 1;
    }

    private static void remove(int key) {
        String id = id(key);
        StringBuilder sb = new StringBuilder();
        for (String[] k : keys()) {
            if (k[0].equals(id) || !k[0].startsWith("k")) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(String.join("|", k));
        }
        for (int m = 0; m < MODE_NAMES.length; m++) {
            set(key, m, null);
        }
        Prefs.setString(Hub.app, HelperRegistry.APP_KEYS, LEARNED, sb.length() == 0 ? null : sb.toString());
        keys = null;
        resetPress();
    }

    /** KEYCODE_PROG_RED becomes "Prog red". */
    private static String label(int code) {
        String s = KeyEvent.keyCodeToString(code).replace("KEYCODE_", "").replace('_', ' ').toLowerCase();
        return s.isEmpty() ? "Key " + code : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Indexes changed: forget the current press. */
    private static void resetPress() {
        Hub.handler.removeCallbacks(longPress);
        Hub.handler.removeCallbacks(singleLater);
        downKey = -1;
        pendingKey = -1;
    }
}
