package tv.bestseat.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reports screen on/off (standby) to the Hub. One instance for all helpers. */
final class ScreenReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Hub.onScreenChanged(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
    }
}
