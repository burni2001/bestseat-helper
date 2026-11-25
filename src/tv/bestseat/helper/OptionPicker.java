package tv.bestseat.helper;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** Simple choice from a few values in the style of the other overlays (e.g. "Screen off after"). */
final class OptionPicker {

    private OptionPicker() {
    }

    static void show(Activity a, String title, String subtitle, String[] labels, int current, IntConsumer onPick) {
        LinearLayout panel = Dialogs.panel(a);
        Dialog dialog = Dialogs.show(a, panel);
        panel.addView(Ui.text(a, title, 26, Ui.TEXT, true));
        TextView sub = Ui.text(a, subtitle, 14, Ui.TEXT_DIM, false);
        sub.setMaxLines(3);
        sub.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 14));
        panel.addView(sub);
        View focus = null;
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            LinearLayout row = Ui.row(a);
            row.setMinimumHeight(Ui.dp(a, 44));
            row.addView(Ui.text(a, labels[i], 17, Ui.TEXT, true), Ui.fill());
            if (i == current) {
                row.addView(Ui.text(a, "Current", 14, Ui.ACCENT, true));
            }
            row.setOnClickListener(v -> {
                dialog.dismiss();
                onPick.accept(index);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = Ui.dp(a, 6);
            panel.addView(row, params);
            if (focus == null || i == current) {
                focus = row;
            }
        }
        dialog.show();
        if (focus != null) {
            focus.requestFocus();
        }
    }
}
