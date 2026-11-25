package tv.bestseat.helper;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Choice of a key's remembered target (Netflix only): nothing, the picture menu, an app from
 * the list, or a freely entered path "package/class".
 */
final class ActionPicker {

    static final String SETTINGS_PKG = "com.android.tv.settings";
    static final String PICTURE_CLS = "com.tpv.tv.settings.picture.PictureActivity";

    private ActionPicker() {
    }

    /** Short text for the row on the detail page. */
    static String describe(Context c, int key) {
        String pkg = Keys.learned(key);
        String cls = Keys.learnedClass(key);
        if (pkg == null) {
            return "Not set";
        }
        if (SETTINGS_PKG.equals(pkg) && PICTURE_CLS.equals(cls)) {
            return "Picture settings";
        }
        String label = label(c, pkg);
        return cls == null ? label : label + " · " + cls.substring(cls.lastIndexOf('.') + 1);
    }

    static void show(Activity a, int key, Runnable done) {
        LinearLayout panel = Dialogs.panel(a);
        Dialog dialog = Dialogs.show(a, panel);
        panel.addView(Ui.text(a, Keys.NAMES[key] + " key target", 26, Ui.TEXT, true));
        TextView sub = Ui.text(a, "Choose what opens after " + Keys.NAMES[key] + " is sent away. "
                + "Your tvQuickActions action is also picked up here when it shows up first.", 14, Ui.TEXT_DIM, false);
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

        String curPkg = Keys.learned(key);
        String curCls = Keys.learnedClass(key);
        List<View> rows = new ArrayList<>();
        rows.add(item(a, list, "Not set", "Back to the previous app", curPkg == null, () -> {
            Keys.setLearned(key, null, null);
            dialog.dismiss();
            done.run();
        }));
        rows.add(item(a, list, "Picture settings", "Picture menu",
                SETTINGS_PKG.equals(curPkg) && PICTURE_CLS.equals(curCls), () -> {
            Keys.setLearned(key, SETTINGS_PKG, PICTURE_CLS);
            dialog.dismiss();
            done.run();
        }));
        rows.add(item(a, list, "Enter activity…", "package/class", false, () -> {
            dialog.dismiss();
            enterPath(a, key, done);
        }));
        for (String[] app : apps(a)) {
            String pkg = app[1];
            rows.add(item(a, list, app[0], "", pkg.equals(curPkg) && curCls == null, () -> {
                Keys.setLearned(key, pkg, null);
                dialog.dismiss();
                done.run();
            }));
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

    static View item(Activity a, LinearLayout list, String title, String detail, boolean current, Runnable click) {
        LinearLayout row = Ui.row(a);
        row.setMinimumHeight(Ui.dp(a, 48));
        row.addView(Ui.text(a, title, 17, Ui.TEXT, true), Ui.fill());
        TextView right = Ui.text(a, current ? "Current" : detail, 14, current ? Ui.ACCENT : Ui.TEXT_DIM, current);
        right.setPadding(Ui.dp(a, 12), 0, 0, 0);
        row.addView(right);
        row.setTag(current);
        row.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(a, 8);
        list.addView(row, params);
        return row;
    }

    /** Free input "package/class" (class optional, ".Name" is completed). */
    private static void enterPath(Activity a, int key, Runnable done) {
        LinearLayout panel = Dialogs.panel(a);
        Dialog dialog = Dialogs.show(a, panel);
        panel.addView(Ui.text(a, "Enter activity", 26, Ui.TEXT, true));
        TextView sub = Ui.text(a, "Enter package/class, or just a package to open the app's start page.", 14, Ui.TEXT_DIM, false);
        sub.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 14));
        panel.addView(sub);

        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.TEXT_DIM);
        input.setHint("com.example.app/com.example.app.SomeActivity");
        input.setTextSize(16);
        input.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.ROW);
        bg.setCornerRadius(Ui.dp(a, 12));
        input.setBackground(bg);
        String pkg = Keys.learned(key);
        String cls = Keys.learnedClass(key);
        if (pkg != null) {
            input.setText(cls == null ? pkg : pkg + "/" + cls);
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
        Runnable trySave = () -> {
            String msg = savePath(a, key, input.getText().toString());
            if (msg == null) {
                dialog.dismiss();
                done.run();
            } else {
                error.setText(msg);
            }
        };
        save.setOnClickListener(v -> trySave.run());
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
        input.requestFocus();
    }

    /** Checks and saves; returns an error message or null. */
    private static String savePath(Context c, int key, String text) {
        String s = text.trim();
        if (s.isEmpty()) {
            Keys.setLearned(key, null, null);
            return null;
        }
        int slash = s.indexOf('/');
        String pkg = slash < 0 ? s : s.substring(0, slash).trim();
        String cls = slash < 0 ? null : s.substring(slash + 1).trim();
        if (cls != null && cls.isEmpty()) {
            cls = null;
        }
        if (cls != null && cls.startsWith(".")) {
            cls = pkg + cls;
        }
        PackageManager pm = c.getPackageManager();
        try {
            pm.getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return "App " + pkg + " is not installed.";
        }
        if (cls != null) {
            try {
                ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, cls), 0);
                if (!ai.exported || ai.permission != null) {
                    return "This activity can't be opened by other apps.";
                }
            } catch (PackageManager.NameNotFoundException e) {
                return "Activity not found in " + pkg + ".";
            }
        } else if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) {
            return "This app has no start page. Enter package/class.";
        }
        Keys.setLearned(key, pkg, cls);
        return null;
    }

    /** Apps from the TV launcher, sorted by name: {name, package}. */
    static List<String[]> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (seen.contains(pkg) || c.getPackageName().equals(pkg)) {
                continue;
            }
            seen.add(pkg);
            out.add(new String[] {String.valueOf(ri.loadLabel(pm)), pkg});
        }
        Collections.sort(out, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        return out;
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
