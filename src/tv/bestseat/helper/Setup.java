package tv.bestseat.helper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * Setup wizard: guides through the settings that depend on the TV on first start.
 * DetailActivity shows pages with a helper, SetupActivity the others.
 */
final class Setup {

    static final String EXTRA_STEP = "setup_step";
    private static final String DONE = "setup.done";

    /** Page order; helpers by id. */
    static final String[] STEPS = {"welcome", "permissions", "app_keys", "accessibility_guard", "system_apps", "finish"};

    private Setup() {
    }

    static boolean done(Context c) {
        return Prefs.get(c).getBoolean(DONE, false);
    }

    static void finish(Activity a) {
        Prefs.get(a).edit().putBoolean(DONE, true).apply();
        a.startActivity(new Intent(a, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        a.finish();
    }

    /** Opens page "step" and closes the current one. */
    static void go(Activity a, int step) {
        String id = STEPS[step];
        Helper h = HelperRegistry.byId(id);
        Intent i = h != null
                ? new Intent(a, DetailActivity.class).putExtra(DetailActivity.EXTRA_ID, id)
                : new Intent(a, SetupActivity.class);
        a.startActivity(i.putExtra(EXTRA_STEP, step));
        a.overridePendingTransition(0, 0);
        a.finish();
    }

    static String progress(int step) {
        return "Setup · step " + (step + 1) + " of " + STEPS.length;
    }

    /** Row with "Back" and "Next" (or "Finish") at the end of a page. */
    static LinearLayout footer(Activity a, int step) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setClipChildren(false);
        bar.setPadding(0, Ui.dp(a, 18), 0, 0);
        if (step > 0) {
            LinearLayout back = button(a, "Back");
            back.setOnClickListener(v -> go(a, step - 1));
            bar.addView(back, weight(a, true));
        }
        boolean last = step == STEPS.length - 1;
        LinearLayout next = button(a, last ? "Finish" : "Next");
        next.setOnClickListener(v -> {
            if (last) finish(a);
            else go(a, step + 1);
        });
        bar.addView(next, weight(a, false));
        return bar;
    }

    private static LinearLayout button(Activity a, String title) {
        LinearLayout b = Ui.row(a);
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(Ui.dp(a, 52));
        b.addView(Ui.text(a, title, 17, Ui.TEXT, true));
        return b;
    }

    private static LinearLayout.LayoutParams weight(Activity a, boolean gapRight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (gapRight) p.rightMargin = Ui.dp(a, 12);
        return p;
    }
}
