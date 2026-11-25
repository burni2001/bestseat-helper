package tv.bestseat.helper;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

/** Checks the permissions that are granted via adb (see the Permissions dialog). */
final class Perms {

    private Perms() {
    }

    static boolean usageAccess(Context context) {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    static boolean overlay(Context context) {
        return Settings.canDrawOverlays(context);
    }

    static boolean batteryExempt(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** Write secure settings (Screen timeout, Accessibility guard). Via pm grant. */
    static boolean writeSecure(Context context) {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Accessibility service listed as enabled (connected or not). */
    static boolean accessibilityListed(Context context) {
        String services = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return services != null && services.contains(context.getPackageName() + "/");
    }
}
