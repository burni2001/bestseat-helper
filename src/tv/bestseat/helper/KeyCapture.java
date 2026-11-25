package tv.bestseat.helper;

import android.view.KeyEvent;

/** Receiver for "Add a key": gets the next key instead of the helpers. */
interface KeyCapture {
    void onKey(KeyEvent event);
}
