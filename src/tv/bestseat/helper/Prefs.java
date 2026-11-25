package tv.bestseat.helper;

import android.content.Context;
import android.content.SharedPreferences;

/** On/off of the helpers and their settings. All helpers are off by default. */
final class Prefs {

    private static final String FILE = "helpers";

    private Prefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context, Helper helper) {
        return get(context).getBoolean(helper.id() + ".enabled", false);
    }

    static void setEnabled(Context context, Helper helper, boolean on) {
        get(context).edit().putBoolean(helper.id() + ".enabled", on).apply();
    }

    static boolean setting(Context context, Helper helper, Setting setting) {
        return get(context).getBoolean(helper.id() + "." + setting.key, setting.byDefault);
    }

    static boolean setting(Context context, Helper helper, String key, boolean byDefault) {
        return get(context).getBoolean(helper.id() + "." + key, byDefault);
    }

    static void setSetting(Context context, Helper helper, String key, boolean value) {
        get(context).edit().putBoolean(helper.id() + "." + key, value).apply();
    }

    static String string(Context context, Helper helper, String key) {
        return get(context).getString(helper.id() + "." + key, null);
    }

    static void setString(Context context, Helper helper, String key, String value) {
        SharedPreferences.Editor e = get(context).edit();
        if (value == null) {
            e.remove(helper.id() + "." + key);
        } else {
            e.putString(helper.id() + "." + key, value);
        }
        e.apply();
    }
}
