package tv.bestseat.helper;

import java.util.Arrays;
import java.util.List;

/** All helpers in UI order. New helper = one line here. */
final class HelperRegistry {

    static final AppKeysHelper APP_KEYS = new AppKeysHelper();
    static final RefreshHelper REFRESH = new RefreshHelper();
    static final ScreenTimeoutHelper SCREEN = new ScreenTimeoutHelper();
    static final AccessibilityGuardHelper GUARD = new AccessibilityGuardHelper();
    static final SystemAppsHelper SYSTEM_APPS = new SystemAppsHelper();
    static final AutostartHelper AUTOSTART = new AutostartHelper();

    static final List<Helper> ALL = Arrays.asList(APP_KEYS, SCREEN, GUARD, AUTOSTART, REFRESH, SYSTEM_APPS);

    private HelperRegistry() {
    }

    static Helper byId(String id) {
        for (Helper h : ALL) {
            if (h.id().equals(id)) {
                return h;
            }
        }
        return null;
    }
}
