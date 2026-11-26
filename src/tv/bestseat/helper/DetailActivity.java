package tv.bestseat.helper;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Detail page of a helper: description, main switch, state and the helper's settings.
 * Below the list, a hint for the focused row.
 */
public final class DetailActivity extends Activity {

    static final String EXTRA_ID = "helper";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshLoop;
    private Helper helper;
    private StatusView status;
    private TextView details;
    /** Main switch of the helper; null for actions (e.g. Refresh home screen). */
    private Switch mainToggle;
    private final List<Setting> settings = new ArrayList<>();
    private final List<Switch> toggles = new ArrayList<>();
    private final List<Choice> choices = new ArrayList<>();
    private final List<TextView> choiceValues = new ArrayList<>();
    private final List<Switch> choiceToggles = new ArrayList<>();
    private final List<View> choiceRowViews = new ArrayList<>();
    /** Index of the switch the choice row hangs below, else -1. */
    private final List<Integer> choiceAnchorIndex = new ArrayList<>();
    private static final int INNER_DP = 4;
    private static final String GROUP_TOP = "group-top";
    private static final float DISABLED_ALPHA = 0.35f;
    private Hint hint;
    /** Row that grants the helper's missing permission; hidden while nothing is missing. */
    private LinearLayout fixRow;
    private TextView fixTitle;
    private TextView fixValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Hub.init(this);
        helper = HelperRegistry.byId(String.valueOf(getIntent().getStringExtra(EXTRA_ID)));
        if (helper == null) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setClipChildren(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(Ui.scrollPage(this, column, 640), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int step = getIntent().getIntExtra(Setup.EXTRA_STEP, -1);
        if (step >= 0) {
            column.addView(Ui.caption(this, Setup.progress(step)));
        }
        column.addView(Ui.text(this, helper.title(), 30, Ui.TEXT, true));
        TextView subtitle = Ui.text(this, helper.description(), 16, Ui.TEXT_DIM, false);
        subtitle.setMaxLines(2);
        subtitle.setPadding(0, dp(2), 0, dp(24));
        column.addView(subtitle);

        hint = new Hint(this);
        View firstRow = null;

        // Main switch at the top: without it the state read as if the helper turned itself on.
        if (!helper.isAction()) {
            LinearLayout mainRow = Ui.row(this);
            mainRow.addView(Ui.text(this, helper.title(), 18, Ui.TEXT, true), Ui.fill());
            mainToggle = Ui.toggle(this);
            mainRow.addView(mainToggle);
            mainRow.setOnClickListener(v -> {
                Prefs.setEnabled(this, helper, !Prefs.enabled(this, helper));
                Hub.sync(this);
                show();
            });
            hint.attach(mainRow, helper::description);
            LinearLayout.LayoutParams mainParams = rowParams();
            mainParams.bottomMargin = dp(18);
            column.addView(mainRow, mainParams);
            firstRow = mainRow;
        }

        // State as a flat info block, with a thin line below.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        status = new StatusView(this, 17);
        status.view.setPadding(dp(20), 0, dp(20), 0);
        panel.addView(status.view);
        details = Ui.text(this, "", 14, Ui.TEXT_DIM, false);
        details.setMaxLines(2);
        details.setPadding(dp(38), dp(4), dp(20), 0);
        panel.addView(details);
        View divider = new View(this);
        divider.setBackgroundColor(Ui.ROW);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(18);
        panel.addView(divider, dividerParams);
        LinearLayout.LayoutParams panelParams = rowParams();
        panelParams.bottomMargin = dp(18);
        column.addView(panel, panelParams);

        fixRow = Ui.row(this);
        fixTitle = Ui.text(this, "", 18, Ui.TEXT, true);
        fixRow.addView(fixTitle, Ui.fill());
        fixValue = Ui.text(this, "", 16, Ui.TEXT_DIM, false);
        fixRow.addView(fixValue);
        fixRow.setOnClickListener(v -> {
            String id = PermissionFix.missing(this, helper);
            if (id != null) {
                PermissionFix.fix(this, id, this::show);
            }
        });
        hint.attach(fixRow, () -> {
            String id = PermissionFix.missing(this, helper);
            return id == null ? "" : PermissionFix.hint(this, id);
        });
        LinearLayout.LayoutParams fixParams = rowParams();
        fixParams.bottomMargin = dp(18);
        column.addView(fixRow, fixParams);

        // Build all rows first, then place them: choice rows with afterSetting go directly below
        // their switch (e.g. "Netflix key target" below "Netflix key").
        List<View> settingRows = new ArrayList<>();
        List<String> settingKeys = new ArrayList<>();
        List<View> choiceRows = new ArrayList<>();
        List<String> choiceAnchors = new ArrayList<>();
        List<Setting> allSettings = helper.settings();
        List<Choice> allChoices = helper.choices();
        List<String> keys = new ArrayList<>();
        for (Setting s : allSettings) {
            keys.add(s.key);
        }
        for (Choice c : allChoices) {
            choiceAnchors.add(keys.contains(c.afterSetting) ? c.afterSetting : null);
        }
        // A switch with attached rows forms a block: 2 dp gap, nearly square inner corners,
        // attached rows greyed out while the switch is off.
        for (Setting s : allSettings) {
            boolean grouped = choiceAnchors.contains(s.key);
            LinearLayout row = grouped ? Ui.row(this, Ui.RADIUS_DP, INNER_DP) : Ui.row(this);
            if (grouped) {
                row.setTag(GROUP_TOP);
            }
            row.addView(Ui.text(this, s.title, 18, Ui.TEXT, true), Ui.fill());
            Switch toggle = Ui.toggle(this);
            row.addView(toggle);
            row.setOnClickListener(v -> {
                boolean value = !Prefs.setting(this, helper, s);
                Prefs.setSetting(this, helper, s.key, value);
                helper.onSettingChanged(s.key, value);
                // Accessibility events can change with the setting.
                Hub.updateAccessibility();
                show();
            });
            hint.attach(row, () -> Prefs.setting(this, helper, s) ? s.hintOn : s.hintOff);
            settings.add(s);
            toggles.add(toggle);
            settingRows.add(row);
            settingKeys.add(s.key);
        }

        for (int ci = 0; ci < allChoices.size(); ci++) {
            Choice c = allChoices.get(ci);
            String anchor = choiceAnchors.get(ci);
            boolean last = anchor != null && choiceAnchors.subList(ci + 1, choiceAnchors.size()).indexOf(anchor) < 0;
            LinearLayout row = anchor == null ? Ui.row(this)
                    : Ui.row(this, INNER_DP, last ? Ui.RADIUS_DP : INNER_DP);
            if (anchor != null && !last) {
                row.setTag(GROUP_TOP);
            }
            choiceAnchorIndex.add(anchor == null ? -1 : keys.indexOf(anchor));
            row.addView(Ui.text(this, c.title, 18, Ui.TEXT, true), Ui.fill());
            TextView value = Ui.text(this, "", 16, Ui.TEXT_DIM, false);
            value.setPadding(dp(16), 0, 0, 0);
            row.addView(value);
            // With only "On"/"Off", the row shows a switch instead of the text.
            Switch choiceToggle = Ui.toggle(this);
            choiceToggle.setVisibility(View.GONE);
            row.addView(choiceToggle);
            choiceToggles.add(choiceToggle);
            row.setOnClickListener(v -> c.open.open(this, this::show));
            hint.attach(row, c.hint);
            choices.add(c);
            choiceValues.add(value);
            choiceRows.add(row);
            choiceRowViews.add(row);
        }

        List<View> ordered = new ArrayList<>();
        for (int i = 0; i < settingRows.size(); i++) {
            ordered.add(settingRows.get(i));
            for (int j = 0; j < choiceRows.size(); j++) {
                if (settingKeys.get(i).equals(choiceAnchors.get(j))) {
                    ordered.add(choiceRows.get(j));
                }
            }
        }
        for (int j = 0; j < choiceRows.size(); j++) {
            if (choiceAnchors.get(j) == null) {
                ordered.add(choiceRows.get(j));
            }
        }
        for (View row : ordered) {
            LinearLayout.LayoutParams params = rowParams();
            if (GROUP_TOP.equals(row.getTag())) {
                params.bottomMargin = dp(2);
            }
            column.addView(row, params);
            if (firstRow == null) {
                firstRow = row;
            }
        }

        column.addView(hint.view);
        String note = helper.detailNote();
        if (note != null) {
            TextView noteView = Ui.text(this, note, 13, Ui.TEXT_DIM, false);
            noteView.setPadding(dp(20), dp(14), dp(20), 0);
            column.addView(noteView);
        }
        if (step >= 0) {
            column.addView(Setup.footer(this, step));
        }

        setContentView(root);
        if (firstRow != null) {
            firstRow.requestFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void refreshLoop() {
        show();
        handler.postDelayed(refresh, 1000);
    }

    private void show() {
        Status s = Hub.status(helper);
        status.set(s);
        if (mainToggle != null) {
            mainToggle.setChecked(Prefs.enabled(this, helper));
        }
        details.setText(s.detail == null ? "" : s.detail);
        String missing = Prefs.enabled(this, helper) ? PermissionFix.missing(this, helper) : null;
        boolean showFix = missing != null;
        fixRow.setVisibility(showFix ? View.VISIBLE : View.GONE);
        if (showFix) {
            fixTitle.setText("Grant " + PermissionFix.title(missing));
            fixValue.setText(PermissionFix.busy(missing) ? "Waiting for the TV…" : "");
        }
        details.setVisibility(s.detail == null || s.detail.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < settings.size(); i++) {
            toggles.get(i).setChecked(Prefs.setting(this, helper, settings.get(i)));
        }
        for (int i = 0; i < choices.size(); i++) {
            String v = choices.get(i).value.get();
            boolean onOff = "On".equals(v) || "Off".equals(v);
            choiceValues.get(i).setText(v);
            choiceValues.get(i).setVisibility(onOff ? View.GONE : View.VISIBLE);
            choiceToggles.get(i).setVisibility(onOff ? View.VISIBLE : View.GONE);
            choiceToggles.get(i).setChecked("On".equals(v));
            int anchor = choiceAnchorIndex.get(i);
            if (anchor >= 0) {
                boolean on = Prefs.setting(this, helper, settings.get(anchor));
                View row = choiceRowViews.get(i);
                row.setAlpha(on ? 1f : DISABLED_ALPHA);
                row.setFocusable(on);
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
