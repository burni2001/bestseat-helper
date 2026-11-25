package tv.bestseat.helper;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Shared UI building blocks. */
final class Ui {

    static final int BACKGROUND = Color.rgb(16, 20, 24);
    static final int PANEL = Color.rgb(30, 30, 34);
    static final int ROW = Color.rgb(34, 36, 42);
    static final int ROW_FOCUSED = Color.rgb(70, 70, 80);
    static final int TEXT = Color.WHITE;
    static final int TEXT_DIM = Color.rgb(160, 164, 172);
    static final int ACCENT = Color.rgb(74, 144, 255);

    /** Corner radius of rows. */
    static final int RADIUS_DP = 18;

    private Ui() {}

    static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** Text in Roboto, bold with slight letter spacing. */
    static TextView text(Context context, CharSequence value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        if (bold) {
            view.setLetterSpacing(0.02f);
        }
        view.setMaxLines(1);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    /** Small upper-case heading for sections. */
    static TextView caption(Context context, String value) {
        TextView view = text(context, value.toUpperCase(), 12, TEXT_DIM, true);
        view.setLetterSpacing(0.12f);
        return view;
    }

    /** Focusable row that gets brighter and grows slightly with focus. */
    static LinearLayout row(Context context) {
        return row(context, RADIUS_DP, RADIUS_DP);
    }

    /**
     * Like row(), with its own top and bottom corners, for rows that belong together.
     */
    static LinearLayout row(Context context, int topDp, int bottomDp) {
        float t = dp(context, topDp);
        float b = dp(context, bottomDp);
        float[] radii = {t, t, t, t, b, b, b, b};
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 20), 0, dp(context, 20), 0);
        row.setMinimumHeight(dp(context, 56));
        row.setFocusable(true);
        row.setClickable(true);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(ROW);
        normal.setCornerRadii(radii);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(ROW_FOCUSED);
        focused.setCornerRadii(radii);
        row.setBackground(normal);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(hasFocus ? focused : normal);
            v.animate().scaleX(hasFocus ? 1.02f : 1f).scaleY(hasFocus ? 1.02f : 1f)
                    .setDuration(120).start();
        });
        return row;
    }

    /** Switch for display only. The row toggles it. */
    static Switch toggle(Context context) {
        Switch toggle = new Switch(context);
        toggle.setFocusable(false);
        toggle.setClickable(false);
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setThumbTintList(new ColorStateList(states,
                new int[] {Color.WHITE, Color.rgb(170, 170, 178)}));
        toggle.setTrackTintList(new ColorStateList(states,
                new int[] {ACCENT, Color.rgb(96, 98, 106)}));
        return toggle;
    }

    /** Layout params for an element that fills the rest of the row. */
    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    /**
     * Page that scrolls once it is taller than the screen (TV: 960×540 dp). Shorter pages stay
     * centred. It scrolls with the focus by itself.
     */
    static android.view.View scrollPage(android.app.Activity a, android.view.View column, int widthDp) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        android.widget.FrameLayout inner = new android.widget.FrameLayout(a);
        inner.setClipChildren(false);
        inner.setClipToPadding(false);
        inner.setPadding(0, dp(a, 32), 0, dp(a, 32));
        inner.addView(column, new android.widget.FrameLayout.LayoutParams(dp(a, widthDp),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
        scroll.addView(inner, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }
}
