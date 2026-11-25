package tv.bestseat.helper;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Coloured dot with a short status text, e.g. in a list row. */
final class StatusView {
    final LinearLayout view;
    private final GradientDrawable dot;
    private final TextView text;

    StatusView(Context context, int sp) {
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        View dotView = new View(context);
        dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dotView.setBackground(dot);
        int size = Ui.dp(context, sp >= 16 ? 10 : 8);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(size, size);
        dotParams.rightMargin = Ui.dp(context, 8);
        view.addView(dotView, dotParams);
        text = Ui.text(context, "", sp, Ui.TEXT_DIM, false);
        view.addView(text);
    }

    void set(Status status) {
        dot.setColor(status.color);
        text.setText(status.text);
        // Without text (e.g. System apps when all is fine) show neither dot nor line.
        boolean empty = status.text == null || status.text.isEmpty();
        view.setVisibility(empty ? android.view.View.GONE : android.view.View.VISIBLE);
    }
}
