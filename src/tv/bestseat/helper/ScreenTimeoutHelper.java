package tv.bestseat.helper;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Screen timeout: keeps the screen off timeout (System "screen_off_timeout") and the Android
 * screensaver (Secure "screensaver_enabled") at the chosen values.
 *
 * The firmware or other apps sometimes reset these values. The helper sets them on start, when
 * the screen turns on and as soon as anything changes them. If something keeps resetting them,
 * it stops after 6 corrections in 10 minutes (no race).
 *
 * Permissions: appops WRITE_SETTINGS allow (timeout), pm grant WRITE_SECURE_SETTINGS (screensaver).
 */
final class ScreenTimeoutHelper extends Helper {

    private static final String SCREEN_OFF = Settings.System.SCREEN_OFF_TIMEOUT;
    private static final String SCREENSAVER = "screensaver_enabled";
    private static final int[] MINUTES = {10, 15, 20, 30, 60, 120, 240};
    private static final int DEFAULT_MINUTES = 30;
    private static final int MAX_FIXES = 6;
    private static final long FIX_WINDOW_MS = 10 * 60 * 1000L;

    private static final Setting SCREENSAVER_SETTING = new Setting("screensaver", "Android screensaver", false,
            "Allow Android's own screensaver. Philips' burn-in protection runs either way.");

    private SettingsObserver observer;
    private final Runnable onChanged = () -> apply("changed elsewhere");
    private final Runnable onScreenOn = () -> apply("screen on");
    private final List<Long> fixes = new ArrayList<>();
    private boolean gaveUp;
    private String lastFix;

    @Override
    String id() {
        return "screen_timeout";
    }

    @Override
    String title() {
        return "Screen timeout";
    }

    @Override
    String description() {
        return "Keep the screen-off time and Android's screensaver the way you set them.";
    }

    @Override
    int icon() {
        return android.R.drawable.ic_lock_idle_alarm;
    }

    @Override
    String[] permissions() {
        return new String[]{PermissionFix.WRITE_SETTINGS, PermissionFix.SECURE};
    }

    @Override
    String missingPermission(Context context) {
        if (!Settings.System.canWrite(context)) {
            return "Permission to change settings is missing";
        }
        if (!Perms.writeSecure(context)) {
            return "Secure settings permission is missing";
        }
        return null;
    }

    @Override
    List<Setting> settings() {
        return Collections.singletonList(SCREENSAVER_SETTING);
    }

    @Override
    void onSettingChanged(String key, boolean value) {
        if (Hub.isRunning(this)) {
            apply("setting");
        }
    }

    @Override
    List<Choice> choices() {
        List<Choice> list = new ArrayList<>();
        list.add(new Choice("Screen off after",
                () -> label(minutes()),
                () -> "Set the time until Android turns the screen off when idle." + sleepNote(),
                (activity, done) -> {
                    String[] labels = new String[MINUTES.length];
                    int current = -1;
                    for (int i = 0; i < MINUTES.length; i++) {
                        labels[i] = label(MINUTES[i]);
                        if (MINUTES[i] == minutes()) {
                            current = i;
                        }
                    }
                    OptionPicker.show(activity, "Screen off after", "Set the time until Android turns the screen off when idle.",
                            labels, current, i -> {
                                Prefs.setString(Hub.app, this, "minutes", String.valueOf(MINUTES[i]));
                                if (Hub.isRunning(this)) {
                                    apply("setting");
                                }
                                done.run();
                            });
                }));
        return list;
    }

    @Override
    void start() {
        fixes.clear();
        gaveUp = false;
        ContentResolver cr = Hub.app.getContentResolver();
        observer = new SettingsObserver(Hub.handler, () -> {
            Hub.handler.removeCallbacks(onChanged);
            Hub.handler.postDelayed(onChanged, 1500);
        });
        cr.registerContentObserver(Settings.System.getUriFor(SCREEN_OFF), false, observer);
        cr.registerContentObserver(Settings.Secure.getUriFor(SCREENSAVER), false, observer);
        apply("start");
    }

    @Override
    void stop() {
        if (observer != null) {
            Hub.app.getContentResolver().unregisterContentObserver(observer);
            observer = null;
        }
        Hub.handler.removeCallbacks(onChanged);
        Hub.handler.removeCallbacks(onScreenOn);
    }

    @Override
    void onScreenChanged(boolean on) {
        if (on) {
            // Wait briefly after wake, in case the firmware sets something itself on power on.
            Hub.handler.removeCallbacks(onScreenOn);
            Hub.handler.postDelayed(onScreenOn, 5000);
        }
    }

    @Override
    Status runningStatus() {
        if (gaveUp) {
            return Status.bad("Keeps being changed back",
                    "Something resets these settings again and again. Turn the helper off and on to try again.");
        }
        String text = "Screen off after " + label(minutes()) + " · Android screensaver "
                + (Prefs.setting(Hub.app, this, SCREENSAVER_SETTING) ? "on" : "off");
        return Status.good("Active", text + (lastFix != null ? ". Last fixed " + lastFix : ""));
    }

    // ------------------------------------------------------------------ Setting values

    private void apply(String reason) {
        if (!Hub.isRunning(this) && !"start".equals(reason)) {
            return;
        }
        if (gaveUp && "changed elsewhere".equals(reason)) {
            return;
        }
        ContentResolver cr = Hub.app.getContentResolver();
        List<String> changed = new ArrayList<>();
        try {
            if (Settings.System.canWrite(Hub.app)) {
                int want = minutes() * 60000;
                int cur = Settings.System.getInt(cr, SCREEN_OFF, -1);
                if (cur != want) {
                    Settings.System.putInt(cr, SCREEN_OFF, want);
                    changed.add("screen off was " + (cur > 0 ? label(cur / 60000) : String.valueOf(cur)));
                }
            }
            if (Perms.writeSecure(Hub.app)) {
                int want = Prefs.setting(Hub.app, this, SCREENSAVER_SETTING) ? 1 : 0;
                int cur = Settings.Secure.getInt(cr, SCREENSAVER, -1);
                if (cur != want) {
                    Settings.Secure.putInt(cr, SCREENSAVER, want);
                    changed.add("screensaver was " + (cur == 1 ? "on" : "off"));
                }
            }
        } catch (RuntimeException e) {
            Log.w(Hub.TAG, "Screen timeout: could not write settings", e);
            return;
        }
        if (changed.isEmpty()) {
            return;
        }
        String what = String.join(", ", changed);
        Hub.log("Screen timeout (" + reason + "): " + what);
        if (!"setting".equals(reason) && !"start".equals(reason)) {
            lastFix = new SimpleDateFormat("HH:mm", Locale.US).format(new Date()) + " (" + what + ")";
        }
        if ("changed elsewhere".equals(reason)) {
            long now = System.currentTimeMillis();
            fixes.removeIf(t -> now - t > FIX_WINDOW_MS);
            fixes.add(now);
            if (fixes.size() >= MAX_FIXES) {
                gaveUp = true;
                Hub.log("Screen timeout: changed back " + MAX_FIXES + " times in 10 minutes, giving up");
            }
        }
    }

    private int minutes() {
        String s = Prefs.string(Hub.app, this, "minutes");
        try {
            return s == null ? DEFAULT_MINUTES : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return DEFAULT_MINUTES;
        }
    }

    private static String label(int minutes) {
        if (minutes >= 60 && minutes % 60 == 0) {
            return minutes == 60 ? "1 hour" : (minutes / 60) + " hours";
        }
        return minutes + " min";
    }

    /** Hint about the TV's sleep timer (Secure "sleep_timeout"), which applies independently. */
    private static String sleepNote() {
        int ms = Settings.Secure.getInt(Hub.app.getContentResolver(), "sleep_timeout", -1);
        if (ms <= 0) {
            return "";
        }
        return " TV sleep timer: " + label(ms / 60000) + ".";
    }
}
