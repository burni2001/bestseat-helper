package tv.bestseat.helper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

/**
 * The single foreground service. Keeps the process alive while a helper that needs it is
 * running. If the process restarts through START_STICKY, it brings the helpers back.
 */
public final class HostService extends Service {

    private static final String CHANNEL = "helpers";

    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, HostService.class));
        } catch (RuntimeException e) {
            Log.w(Hub.TAG, "Could not start service", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Hub.init(this);
        Hub.onServiceCreated();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Best Seat Helper", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Best Seat Helper active")
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        Hub.log("Service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Hub.sync(this);
        if (!Hub.serviceNeeded()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Hub.onServiceDestroyed();
        Hub.log("Service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
