package tv.bestseat.helper;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Refresh home screen: button helper.
 *
 * Some launchers (e.g. AT4K) only reload their app list on PACKAGE_ADDED/REMOVED/CHANGED, not on
 * EXTERNAL_APPLICATIONS_AVAILABLE. Toggling the dummy component RefreshPingReceiver makes the
 * system send PACKAGE_CHANGED for this app (about 1 s later, up to 10 s shortly after boot).
 * Helps when apps on USB storage are missing from the home screen.
 */
final class RefreshHelper extends Helper {

    @Override
    String id() {
        return "refresh_home";
    }

    @Override
    String title() {
        return "Refresh home screen";
    }

    @Override
    String description() {
        return "Reload the home screen's apps, e.g. when USB drive apps are missing.";
    }

    @Override
    int icon() {
        return android.R.drawable.ic_popup_sync;
    }

    @Override
    boolean isAction() {
        return true;
    }

    @Override
    boolean providedByHome() {
        return true;
    }

    @Override
    void runAction(Context context) {
        if (Hub.launcherIsHome()) {
            // Best Seat reloads by itself; the command for other key apps (RefreshActivity) stays valid.
            try {
                context.startActivity(new android.content.Intent("tv.bestseat.launcher.REFRESH")
                        .setPackage(Hub.HOME_LAUNCHER).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                Hub.log("Refresh: forwarded to Best Seat");
            } catch (RuntimeException e) {
                Log.w(Hub.TAG, "Refresh: Best Seat not reachable", e);
            }
            return;
        }
        PackageManager pm = context.getPackageManager();
        ComponentName c = new ComponentName(context, RefreshPingReceiver.class);
        int next = pm.getComponentEnabledSetting(c) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        try {
            pm.setComponentEnabledSetting(c, next, PackageManager.DONT_KILL_APP);
            Toast.makeText(context, "Refreshing home screen…", Toast.LENGTH_SHORT).show();
            Hub.log("Refresh: PACKAGE_CHANGED requested");
        } catch (RuntimeException e) {
            Log.w(Hub.TAG, "Refresh: could not request PACKAGE_CHANGED", e);
            Toast.makeText(context, "Could not refresh the home screen", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    void start() {
    }

    @Override
    void stop() {
    }
}
