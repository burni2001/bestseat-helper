package tv.bestseat.helper;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Main page: one row per helper with icon, name, status and (for continuous helpers) a switch.
 * The row opens the helper's detail page; the switch next to it turns the helper on and off.
 * Below: Permissions, Setup wizard, Backup and About.
 */
public final class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshLoop;
    private final List<Helper> helpers = new ArrayList<>();
    private final List<StatusView> statuses = new ArrayList<>();
    private final List<Switch> toggles = new ArrayList<>();
    private Hint hint;
    private View firstRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Hub.init(this);
        if (!Setup.done(this)) {
            Setup.go(this, 0);
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setClipChildren(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        // Focused rows grow slightly beyond their space.
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(Ui.scrollPage(this, column, 640), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(Ui.text(this, "Best Seat Helper", 30, Ui.TEXT, true));
        TextView subtitle = Ui.text(this, "Small helpers for your TV", 16, Ui.TEXT_DIM, false);
        subtitle.setPadding(0, dp(2), 0, dp(24));
        column.addView(subtitle);

        hint = new Hint(this);
        for (Helper h : HelperRegistry.ALL) {
            // Taken over by the launcher: hide it (settings are kept).
            if (Hub.homeProvides(h)) continue;
            column.addView(helperRow(h), rowParams());
        }

        LinearLayout.LayoutParams gap = rowParams();
        gap.topMargin = dp(14);
        LinearLayout perms = Ui.row(this);
        perms.addView(Ui.text(this, "Permissions", 18, Ui.TEXT, true), Ui.fill());
        perms.setOnClickListener(v -> Dialogs.permissions(this));
        hint.attach(perms, () -> "Check which permissions are granted and how to grant the missing ones.");
        column.addView(perms, gap);

        LinearLayout setup = Ui.row(this);
        setup.addView(Ui.text(this, "Setup wizard", 18, Ui.TEXT, true), Ui.fill());
        setup.setOnClickListener(v -> Setup.go(this, 0));
        hint.attach(setup, () -> "Run the setup for this TV again.");
        column.addView(setup, rowParams());

        LinearLayout backup = Ui.row(this);
        backup.addView(Ui.text(this, "Backup", 18, Ui.TEXT, true), Ui.fill());
        backup.setOnClickListener(v -> Backup.show(this));
        hint.attach(backup, () -> "Save your settings to the TV or restore them.");
        column.addView(backup, rowParams());

        LinearLayout about = Ui.row(this);
        about.addView(Ui.text(this, "About", 18, Ui.TEXT, true), Ui.fill());
        about.setOnClickListener(v -> Dialogs.about(this));
        hint.attach(about, () -> "Show version, creator and credits.");
        column.addView(about, rowParams());

        column.addView(hint.view);
        setContentView(root);
        firstRow.requestFocus();
    }

    private View helperRow(Helper h) {
        LinearLayout row = Ui.row(this);
        row.setMinimumHeight(dp(72));

        ImageView icon = new ImageView(this);
        icon.setImageResource(h.icon());
        icon.setImageTintList(ColorStateList.valueOf(Ui.TEXT));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        iconParams.rightMargin = dp(18);
        row.addView(icon, iconParams);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, h.title(), 18, Ui.TEXT, true));
        StatusView status = new StatusView(this, 14);
        status.view.setPadding(0, dp(3), 0, 0);
        texts.addView(status.view);
        row.addView(texts, Ui.fill());

        helpers.add(h);
        statuses.add(status);

        // The row opens the helper's settings; the switch next to it turns the helper on and off.
        row.setOnClickListener(v -> {
            if (h.isAction()) {
                h.runAction(this);
                show();
            } else {
                openDetail(h);
            }
        });
        hint.attach(row, () -> h.isAction() ? h.description() + " " + h.actionHint()
                : "Open the settings for " + h.title() + ".");
        if (firstRow == null) {
            firstRow = row;
        }

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setClipChildren(false);
        line.addView(row, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = null;
        if (!h.isAction()) {
            LinearLayout box = Ui.row(this);
            box.setGravity(Gravity.CENTER);
            box.setPadding(0, 0, 0, 0);
            toggle = Ui.toggle(this);
            box.addView(toggle);
            box.setOnClickListener(v -> {
                Prefs.setEnabled(this, h, !Prefs.enabled(this, h));
                Hub.sync(this);
                show();
            });
            hint.attach(box, h::description);
            LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(dp(96), dp(72));
            boxParams.leftMargin = dp(10);
            boxParams.gravity = Gravity.CENTER_VERTICAL;
            line.addView(box, boxParams);
        } else {
            // Placeholder so all rows have the same width.
            line.addView(new View(this), new LinearLayout.LayoutParams(dp(106), dp(1)));
        }
        toggles.add(toggle);
        return line;
    }

    private void openDetail(Helper h) {
        startActivity(new Intent(this, DetailActivity.class).putExtra(DetailActivity.EXTRA_ID, h.id()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Hub.sync(this);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    /** Refreshes the display every second while the app is in front. */
    private void refreshLoop() {
        show();
        handler.postDelayed(refresh, 1000);
    }

    private void show() {
        for (int i = 0; i < helpers.size(); i++) {
            Helper h = helpers.get(i);
            statuses.get(i).set(Hub.status(h));
            Switch toggle = toggles.get(i);
            if (toggle != null) {
                toggle.setChecked(Prefs.enabled(this, h));
            }
        }
        hint.refresh();
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        return params;
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
