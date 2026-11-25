package tv.bestseat.helper;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The single accessibility service for all helpers. Hub.updateAccessibility() decides what it
 * watches, based on the running helpers. Keys are watched; only explicitly mapped function keys
 * are consumed. On errors always false, so no key is lost.
 *
 * Note: "am force-stop" on this app turns the accessibility service off.
 */
public final class HostAccessibilityService extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        Hub.onAccessibilityConnected(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null) {
            Hub.onAccessibilityEvent(event);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            return Hub.onKeyEvent(event);
        } catch (RuntimeException e) {
            Log.w(Hub.TAG, "Key event", e);
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Hub.onAccessibilityConnected(null);
        return super.onUnbind(intent);
    }
}
