package tv.bestseat.helper;

import android.app.Activity;

/** Opens a Choice's picker; call done after a change. */
interface ChoiceOpener {
    void open(Activity activity, Runnable done);
}
