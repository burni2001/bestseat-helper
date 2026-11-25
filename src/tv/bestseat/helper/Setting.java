package tv.bestseat.helper;

/** Switch on a helper's detail page. Stored as "<helper-id>.<key>". */
final class Setting {
    final String key;
    final String title;
    final boolean byDefault;
    final String hintOn;
    final String hintOff;

    /** A hint that describes the purpose, independent of the state. */
    Setting(String key, String title, boolean byDefault, String hint) {
        this(key, title, byDefault, hint, hint);
    }

    Setting(String key, String title, boolean byDefault, String hintOn, String hintOff) {
        this.key = key;
        this.title = title;
        this.byDefault = byDefault;
        this.hintOn = hintOn;
        this.hintOff = hintOff;
    }
}
