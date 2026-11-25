package tv.bestseat.helper;

import android.os.Build;

/** Which TV: Philips sets (manufacturer TPV/TP Vision) have their own keys and apps. */
final class Device {

    static final boolean PHILIPS = matches(Build.MANUFACTURER, "tpv", "tp vision", "philips")
            || matches(Build.BRAND, "philips");

    private Device() {
    }

    private static boolean matches(String value, String... names) {
        String v = value == null ? "" : value.toLowerCase();
        for (String n : names) {
            if (v.contains(n)) return true;
        }
        return false;
    }
}
