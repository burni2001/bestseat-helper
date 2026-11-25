package tv.bestseat.helper;

import android.view.KeyEvent;

/**
 * The three fixed app keys of the Philips remote and the apps that the Philips service
 * org.droidtv.GlobalKey starts for them (found with getevent/logcat).
 */
final class Keys {

    static final int PRIME = 0;
    static final int NETFLIX = 1;
    static final int YOUTUBE = 2;

    static final String[] NAMES = {"Prime Video", "Netflix", "YouTube"};
    /** Setting keys. */
    static final String[] PREFS = {"prime", "netflix", "youtube"};
    /** Android key code (Philips-specific codes). */
    static final int[] CODES = {788, 475, 781};
    /** Scan code of the remote, in case the key code ever arrives differently. */
    static final int[] SCANS = {774, 772, 776};
    /** What GlobalKey starts. Prime and YouTube on key up, Netflix already on key down. */
    static final String[] TARGETS = {
            "com.amazon.amazonvideo.livingroom",
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
    };

    private Keys() {
    }

    /** Index of the app key or -1. */
    static int indexOf(KeyEvent event) {
        for (int i = 0; i < CODES.length; i++) {
            if (event.getKeyCode() == CODES[i] || event.getScanCode() == SCANS[i]) {
                return i;
            }
        }
        return -1;
    }

    /** By default all three keys are freed. */
    static boolean enabled(int key) {
        return Prefs.setting(Hub.app, HelperRegistry.APP_KEYS, PREFS[key], true);
    }

    /**
     * Only the Netflix key remembers a target. Netflix starts on key down and is almost always
     * ahead of the action of another key app, which then never becomes visible (e.g. the picture
     * menu). Prime and YouTube start on key up, when the action is already in front and is
     * detected without remembering.
     */
    static boolean remembers(int key) {
        return key == NETFLIX;
    }

    /** Remembered app of this key, or null. */
    static String learned(int key) {
        return remembers(key) ? Prefs.string(Hub.app, HelperRegistry.APP_KEYS, PREFS[key] + "_app") : null;
    }

    /** Remembered activity of this key (e.g. the picture menu), or null = the app's start page. */
    static String learnedClass(int key) {
        return remembers(key) ? Prefs.string(Hub.app, HelperRegistry.APP_KEYS, PREFS[key] + "_class") : null;
    }

    static void setLearned(int key, String pkg, String cls) {
        Prefs.setString(Hub.app, HelperRegistry.APP_KEYS, PREFS[key] + "_app", pkg);
        Prefs.setString(Hub.app, HelperRegistry.APP_KEYS, PREFS[key] + "_class", pkg == null ? null : cls);
    }

    /** Clears remembered values of keys that don't remember anything. */
    static void forgetLearned() {
        for (int i = 0; i < PREFS.length; i++) {
            if (!remembers(i)) {
                setLearned(i, null, null);
            }
        }
    }
}
