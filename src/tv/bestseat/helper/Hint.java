package tv.bestseat.helper;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Hint below the list that matches the focused row (instead of a fixed text).
 * Small font, always exactly one line, so the centred list doesn't jump when it changes.
 * Own file instead of an inner class of Ui, because d8 fails on inner classes.
 */
final class Hint {
    final TextView view;
    private final Map<View, Supplier<String>> texts = new HashMap<>();
    private View current;

    Hint(Context context) {
        view = Ui.text(context, "", 13, Ui.TEXT_DIM, false);
        view.setPadding(Ui.dp(context, 20), Ui.dp(context, 6), Ui.dp(context, 20), 0);
    }

    /** The text is read fresh on every focus and refresh(), so it can depend on the state. */
    void attach(View row, Supplier<String> text) {
        texts.put(row, text);
        View.OnFocusChangeListener previous = row.getOnFocusChangeListener();
        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (previous != null) previous.onFocusChange(v, hasFocus);
            if (hasFocus) {
                current = v;
                refresh();
            }
        });
    }

    /** Call after a change to the focused row. */
    void refresh() {
        Supplier<String> text = texts.get(current);
        view.setText(text == null ? "" : text.get());
    }
}
