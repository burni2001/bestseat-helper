package tv.bestseat.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the enabled helpers after boot and after an update. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Hub.log("Boot/update: " + intent.getAction());
        Hub.sync(context);
    }
}
