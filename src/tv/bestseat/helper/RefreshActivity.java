package tv.bestseat.helper;

import android.app.Activity;
import android.os.Bundle;

/**
 * Invisible "Refresh home screen" command for remote key apps.
 * E.g. tvQuickActions: Intent, type Activity, action tv.bestseat.helper.REFRESH_HOME
 * (or the app activity "Refresh home screen"). Does the same as OK on the helper's row.
 */
public final class RefreshActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Hub.init(getApplicationContext());
        HelperRegistry.REFRESH.runAction(getApplicationContext());
        finish();
    }
}
