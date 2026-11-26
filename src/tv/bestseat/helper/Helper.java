package tv.bestseat.helper;

import android.content.Context;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.List;

/**
 * A helper. Lives as a single instance in HelperRegistry, started and stopped by Hub.
 *
 * New helper: extend this class and add it to HelperRegistry.ALL. UI, on/off, boot and status
 * come automatically. All calls run on the main thread.
 */
abstract class Helper {

    /** Permanent key, e.g. "app_keys". Never change it (prefs depend on it). */
    abstract String id();

    abstract String title();

    /** One line for the list. */
    abstract String description();

    /** System icon (android.R.drawable.*). */
    abstract int icon();

    // ---------------------------------------------------------------- Helper type

    /** Button helper: no on/off, OK runs runAction(). */
    boolean isAction() {
        return false;
    }

    /** For button helpers: run the function. */
    void runAction(Context context) {
    }

    /** For button helpers: hint on the main page after the description. */
    String actionHint() {
        return "Press OK to run.";
    }

    /** For button helpers: state on the main page. */
    Status actionStatus() {
        return Status.good("Ready", null);
    }

    /** Needs the foreground service while running. */
    boolean needsService() {
        return false;
    }

    /** Only runs with a connected accessibility service (status red otherwise). */
    boolean needsAccessibility() {
        return false;
    }

    /**
     * Does the Best Seat launcher do the same? Then the helper is hidden and stopped while that
     * launcher is the home screen. Settings are kept.
     */
    boolean providedByHome() {
        return false;
    }

    /** Permissions the helper needs (PermissionFix ids), apart from the accessibility service. */
    String[] permissions() {
        return new String[0];
    }

    /** Missing permission as a short text, or null. */
    String missingPermission(Context context) {
        return null;
    }

    // ---------------------------------------------------------------- Lifecycle

    abstract void start();

    abstract void stop();

    /** Turns own components (activities, receivers) on or off to match the state. */
    void applyComponents(Context context, boolean running) {
    }

    /** Status while the helper runs. */
    Status runningStatus() {
        return Status.good("Active", null);
    }

    // ---------------------------------------------------------------- Settings

    List<Setting> settings() {
        return Collections.emptyList();
    }

    /** A setting was toggled in the UI (already saved). */
    void onSettingChanged(String key, boolean value) {
    }

    /** Choice rows below the switches on the detail page (e.g. the remembered Netflix target). */
    List<Choice> choices() {
        return Collections.emptyList();
    }

    /** Extra note below the settings on the detail page, or null. */
    String detailNote() {
        return null;
    }

    // ---------------------------------------------------------------- Events

    void onScreenChanged(boolean on) {
    }

    /** Accessibility event types the helper needs (0 = none). */
    int accessibilityEventTypes() {
        return 0;
    }

    /** Packages whose events the helper needs. */
    String[] accessibilityPackages() {
        return new String[0];
    }

    /** Watch keys (never consume them). */
    boolean wantsKeyEvents() {
        return false;
    }

    void onAccessibilityConnected(boolean connected) {
    }

    void onAccessibilityEvent(AccessibilityEvent event) {
    }

    /** Watch a key. */
    void onKeyEvent(KeyEvent event) {
    }

    /**
     * Consume the key? Only for explicitly mapped keys (function keys in Remote keys).
     * Everything else passes on.
     */
    boolean consumesKey(KeyEvent event) {
        return false;
    }
}
