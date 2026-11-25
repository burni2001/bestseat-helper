package tv.bestseat.helper;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Setup wizard pages without their own helper: welcome, permissions, finish. */
public class SetupActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Hub.init(this);
        int step = getIntent().getIntExtra(Setup.EXTRA_STEP, 0);
        String page = Setup.STEPS[step];

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setClipChildren(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(Ui.scrollPage(this, column, 640), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(Ui.caption(this, Setup.progress(step)));
        View first = null;
        switch (page) {
            case "welcome":
                title(column, "Welcome");
                text(column, "Set up Best Seat Helper for this TV (" + Build.MANUFACTURER + " " + Build.MODEL
                        + "). Every step can be changed later in the settings.");
                first = row(column, "Restore a backup", () -> Backup.show(this));
                break;
            case "permissions":
                title(column, "Permissions");
                text(column, "Grant these once from a computer with adb. Each helper only needs some of them.");
                Dialogs.permissionItems(this, column);
                first = row(column, "Check again", this::recreate);
                break;
            default:
                title(column, "All set");
                text(column, "Turn on the helpers you want on the main page. Run this setup again from there at any time.");
                break;
        }
        LinearLayout footer = Setup.footer(this, step);
        column.addView(footer);
        setContentView(root);
        (first != null ? first : footer.getChildAt(footer.getChildCount() - 1)).requestFocus();
    }

    private void title(LinearLayout column, String value) {
        column.addView(Ui.text(this, value, 30, Ui.TEXT, true));
    }

    private void text(LinearLayout column, String value) {
        TextView t = Ui.text(this, value, 16, Ui.TEXT_DIM, false);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 20));
        column.addView(t);
    }

    private View row(LinearLayout column, String title, Runnable click) {
        LinearLayout r = Ui.row(this);
        r.addView(Ui.text(this, title, 18, Ui.TEXT, true), Ui.fill());
        r.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, 14);
        column.addView(r, p);
        return r;
    }
}
