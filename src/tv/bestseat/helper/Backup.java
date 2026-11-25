package tv.bestseat.helper;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Saves all settings as JSON to the TV's Download folder and restores them.
 * Writing and reading go through the app's own adb shell, so the app needs no storage
 * permission and the file survives a reinstall.
 */
final class Backup {

    static final String FILE = "/sdcard/Download/bestseat-helper-backup.json";
    private static final String FORMAT = "bestseat-helper";

    private Backup() {
    }

    /** Overlay with "Back up" and "Restore". */
    static void show(Activity a) {
        LinearLayout panel = Dialogs.panel(a);
        panel.addView(Ui.text(a, "Backup", 24, Ui.TEXT, true));
        TextView status = Ui.text(a, "File: " + FILE, 15, Ui.TEXT_DIM, false);
        status.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 16));
        panel.addView(status);
        Dialog dialog = Dialogs.show(a, panel);

        LinearLayout save = button(a, panel, "Back up settings");
        save.setOnClickListener(v -> run(a, status, true));
        LinearLayout load = button(a, panel, "Restore backup");
        load.setOnClickListener(v -> run(a, status, false));
        LinearLayout close = button(a, panel, "Close");
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        save.requestFocus();
    }

    private static LinearLayout button(Activity a, LinearLayout panel, String title) {
        LinearLayout row = Ui.row(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(a, 52));
        row.addView(Ui.text(a, title, 17, Ui.TEXT, true));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(a, 8);
        panel.addView(row, params);
        return row;
    }

    private static void run(Activity a, TextView status, boolean save) {
        status.setText(save ? "Saving… Allow USB debugging if the TV asks." : "Restoring…");
        Context app = a.getApplicationContext();
        new Thread(() -> {
            String result;
            try {
                result = save ? save(app) : restore(app);
            } catch (Exception e) {
                result = "Failed: " + e.getMessage();
            }
            String text = result;
            Hub.handler.post(() -> {
                status.setText(text);
                if (!save && text.startsWith("Restored")) a.recreate();
            });
        }, "backup").start();
    }

    /** Writes the backup and returns a message for the UI. */
    static String save(Context context) throws Exception {
        String json = export(context).toString(2);
        String b64 = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String out = AdbClient.shell(context,
                "echo '" + b64 + "' | base64 -d > " + FILE + " && echo ok", 60000);
        if (out == null || !out.trim().endsWith("ok")) throw new Exception(clean(out));
        return "Saved to " + FILE + ".";
    }

    /** Reads the backup and replaces all settings. */
    static String restore(Context context) throws Exception {
        String out = AdbClient.shell(context, "cat " + FILE, 60000);
        int start = out == null ? -1 : out.indexOf('{');
        if (start < 0) throw new Exception("No backup found at " + FILE + ".");
        JSONObject root = new JSONObject(out.substring(start));
        if (!FORMAT.equals(root.optString("format"))) throw new Exception("Not a Best Seat Helper backup.");
        int count = apply(context, root.getJSONObject("prefs"));
        Hub.sync(context);
        return "Restored " + count + " settings.";
    }

    static JSONObject export(Context context) throws Exception {
        JSONObject prefs = new JSONObject();
        for (Map.Entry<String, ?> e : Prefs.get(context).getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject item = new JSONObject();
            if (v instanceof Boolean) item.put("b", v);
            else if (v instanceof Integer) item.put("i", v);
            else if (v instanceof Long) item.put("l", v);
            else if (v instanceof Float) item.put("f", ((Float) v).doubleValue());
            else if (v instanceof Set) item.put("set", new JSONArray((Set<?>) v));
            else if (v != null) item.put("s", v.toString());
            prefs.put(e.getKey(), item);
        }
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", 1);
        root.put("prefs", prefs);
        return root;
    }

    private static int apply(Context context, JSONObject prefs) throws Exception {
        SharedPreferences.Editor e = Prefs.get(context).edit().clear();
        int count = 0;
        for (Iterator<String> it = prefs.keys(); it.hasNext(); count++) {
            String key = it.next();
            JSONObject item = prefs.getJSONObject(key);
            if (item.has("b")) e.putBoolean(key, item.getBoolean("b"));
            else if (item.has("i")) e.putInt(key, item.getInt("i"));
            else if (item.has("l")) e.putLong(key, item.getLong("l"));
            else if (item.has("f")) e.putFloat(key, (float) item.getDouble("f"));
            else if (item.has("set")) {
                JSONArray arr = item.getJSONArray("set");
                Set<String> set = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
                e.putStringSet(key, set);
            } else if (item.has("s")) e.putString(key, item.getString("s"));
        }
        e.commit();
        return count;
    }

    private static String clean(String out) {
        return out == null || out.trim().isEmpty() ? "No answer from the shell." : out.trim();
    }
}
