package tv.bestseat.helper;


import java.util.function.Supplier;

/**
 * Choice row on the detail page: title on the left, current value on the right, a click opens a choice.
 * Own file instead of an inner class, because d8 fails on inner classes.
 */
final class Choice {
    final String title;
    final Supplier<String> value;
    final Supplier<String> hint;
    /** Opens the choice. Call the Runnable afterwards so the row is redrawn. */
    final ChoiceOpener open;
    /** Key of a switch: the row goes directly below it instead of at the end. */
    String afterSetting;

    Choice(String title, Supplier<String> value, Supplier<String> hint, ChoiceOpener open) {
        this.title = title;
        this.value = value;
        this.hint = hint;
        this.open = open;
    }

    /** Places the row directly below the switch with this key. */
    Choice after(String settingKey) {
        afterSetting = settingKey;
        return this;
    }
}
