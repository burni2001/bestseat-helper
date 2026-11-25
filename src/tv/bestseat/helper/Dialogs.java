package tv.bestseat.helper;

import java.util.List;
import android.widget.ScrollView;
import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Overlays: About, Permissions and lists. */
final class Dialogs {

    private Dialogs() {
    }

    static void about(Activity a) {
        LinearLayout panel = panel(a);
        Dialog dialog = show(a, panel);
        panel.addView(Ui.text(a, "Best Seat Helper", 26, Ui.TEXT, true));
        TextView version = Ui.text(a, versionText(a), 16, Ui.TEXT_DIM, false);
        version.setPadding(0, Ui.dp(a, 2), 0, 0);
        panel.addView(version);
        section(a, panel, "Created by", "Made by Börni (burni2001) with Claude");
        section(a, panel, "Credits",
                "Built with Claude Code by Anthropic",
                "Remote button mapping via tvQuickActions",
                "Look and feel matched to App Switcher and AT4K");
        section(a, panel, "Package", a.getPackageName());
        closeButton(a, panel, dialog);
    }

    static void permissions(Activity a) {
        LinearLayout panel = panel(a);
        Dialog dialog = show(a, panel);
        panel.addView(Ui.text(a, "Permissions", 26, Ui.TEXT, true));
        TextView sub = Ui.text(a, "Granted once via adb. Needed only by the helpers you use.", 14, Ui.TEXT_DIM, false);
        sub.setPadding(0, Ui.dp(a, 2), 0, 0);
        panel.addView(sub);
        permissionItems(a, panel);
        closeButton(a, panel, dialog);
    }

    private static void item(Activity a, LinearLayout panel, String name, boolean ok, String how) {
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, Ui.dp(a, 10), 0, 0);
        line.addView(Ui.text(a, name, 17, Ui.TEXT, true), Ui.fill());
        line.addView(Ui.text(a, ok ? "On" : "Off", 16, ok ? Status.GOOD : Status.BAD, true));
        panel.addView(line);
        if (!ok) {
            panel.addView(Ui.text(a, how, 13, Ui.TEXT_DIM, false));
        }
    }

    static LinearLayout panel(Activity a) {
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(a, 32), Ui.dp(a, 28), Ui.dp(a, 32), Ui.dp(a, 24));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Ui.PANEL);
        background.setCornerRadius(Ui.dp(a, 19));
        panel.setBackground(background);
        // Clicks on the panel itself must not close the overlay.
        panel.setClickable(true);
        panel.setFocusable(View.NOT_FOCUSABLE);
        return panel;
    }

    static Dialog show(Activity a, LinearLayout panel) {
        Dialog dialog = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
        FrameLayout dim = new FrameLayout(a);
        dim.setBackgroundColor(Color.argb(170, 0, 0, 0));
        dim.setOnClickListener(v -> dialog.dismiss());
        dim.setFocusable(View.NOT_FOCUSABLE);
        dim.setClipChildren(false);
        dim.addView(panel, new FrameLayout.LayoutParams(
                Ui.dp(a, 520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        dialog.setContentView(dim);
        return dialog;
    }

    private static void closeButton(Activity a, LinearLayout panel, Dialog dialog) {
        LinearLayout close = Ui.row(a);
        close.setGravity(Gravity.CENTER);
        close.setMinimumHeight(Ui.dp(a, 48));
        close.addView(Ui.text(a, "Close", 16, Ui.TEXT, true));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.topMargin = Ui.dp(a, 24);
        panel.addView(close, closeParams);
        dialog.show();
        close.requestFocus();
    }

    private static void section(Activity a, LinearLayout panel, String title, String... lines) {
        TextView caption = Ui.caption(a, title);
        caption.setPadding(0, Ui.dp(a, 20), 0, Ui.dp(a, 4));
        panel.addView(caption);
        for (String line : lines) {
            panel.addView(Ui.text(a, line, 16, Ui.TEXT, false));
        }
    }

    private static String versionText(Activity a) {
        try {
            PackageInfo info = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return "Version " + info.versionName + " · build " + info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return "Version unknown";
        }
    }

    /**
     * Choice list in an overlay: {title, detail} per row, a click returns the index.
     * An empty list only shows the hint.
     */
    static void pickList(Activity a, String title, String subtitle, List<String[]> items,
                         java.util.function.IntConsumer click) {
        LinearLayout panel = panel(a);
        Dialog dialog = show(a, panel);
        panel.addView(Ui.text(a, title, 26, Ui.TEXT, true));
        TextView sub = Ui.text(a, items.isEmpty() ? "Nothing to add." : subtitle, 14, Ui.TEXT_DIM, false);
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
        View first = null;
        for (int i = 0; i < items.size(); i++) {
            int index = i;
            View row = ActionPicker.item(a, list, items.get(i)[0], items.get(i)[1], false, () -> {
                dialog.dismiss();
                click.accept(index);
            });
            if (first == null) first = row;
        }
        dialog.show();
        if (first != null) first.requestFocus();
    }

    /** The permissions with state and adb command; also used in the setup wizard. */
    static void permissionItems(Activity a, LinearLayout panel) {
        String pkg = a.getPackageName();
        item(a, panel, "Accessibility service", Hub.accessibility() != null,
                "Settings › Accessibility › Best Seat Helper");
        item(a, panel, "Usage access", Perms.usageAccess(a),
                "appops set " + pkg + " GET_USAGE_STATS allow");
        item(a, panel, "Display over other apps", Perms.overlay(a),
                "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
        item(a, panel, "No battery restrictions", Perms.batteryExempt(a),
                "cmd deviceidle whitelist +" + pkg);
        item(a, panel, "Change settings (Screen timeout)", android.provider.Settings.System.canWrite(a),
                "appops set " + pkg + " WRITE_SETTINGS allow");
        item(a, panel, "Secure settings (Screen timeout, Accessibility guard)", Perms.writeSecure(a),
                "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
        item(a, panel, "Debugging access (System apps)", SystemAppsHelper.adbOk(),
                "System apps › Debugging access, then “Always allow” on the TV");
    }
}
