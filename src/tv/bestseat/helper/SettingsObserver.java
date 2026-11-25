package tv.bestseat.helper;

import android.database.ContentObserver;
import android.os.Handler;

/** Watches a system setting. Own file instead of an anonymous class (d8). */
final class SettingsObserver extends ContentObserver {
    private final Runnable onChange;

    SettingsObserver(Handler handler, Runnable onChange) {
        super(handler);
        this.onChange = onChange;
    }

    @Override
    public void onChange(boolean selfChange) {
        onChange.run();
    }
}
