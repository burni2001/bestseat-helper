package tv.bestseat.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Never receives anything. Its only purpose: toggling this component makes the system send
 * PACKAGE_CHANGED for this app, which makes the launcher reload its app list.
 */
public final class RefreshPingReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // unused
    }
}
