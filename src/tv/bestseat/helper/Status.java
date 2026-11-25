package tv.bestseat.helper;

import android.graphics.Color;

/** State of a helper: dot colour, short text, optional explanation. */
final class Status {
    static final int GOOD = Color.rgb(52, 199, 89);
    static final int BAD = Color.rgb(255, 92, 80);
    static final int IDLE = Color.rgb(110, 112, 120);

    final int color;
    final String text;
    final String detail;

    private Status(int color, String text, String detail) {
        this.color = color;
        this.text = text;
        this.detail = detail;
    }

    static Status good(String text, String detail) {
        return new Status(GOOD, text, detail);
    }

    static Status idle(String text, String detail) {
        return new Status(IDLE, text, detail);
    }

    static Status bad(String text, String detail) {
        return new Status(BAD, text, detail);
    }
}
