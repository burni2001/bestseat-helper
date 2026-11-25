package tv.bestseat.helper;

import java.util.HashSet;
import java.util.Set;

/**
 * TV state for Autostart: front app, playback, running apps.
 * Own file instead of an inner class, because d8 fails on inner classes.
 */
final class State {
    String frontPkg;
    String frontCls;
    boolean playing;
    final Set<String> running = new HashSet<>();

    /** A switch disturbs nothing: no playback, no live TV/HDMI in front. */
    boolean idle() {
        return !playing && !"org.droidtv.playtv".equals(frontPkg);
    }
}
