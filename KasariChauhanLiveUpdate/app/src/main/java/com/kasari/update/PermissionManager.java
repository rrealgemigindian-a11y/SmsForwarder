package com.kasari.update;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class PermissionManager {

    static final int REQ_CODE = 2001;

    // All permissions the app needs
    static final String[] ALL_PERMISSIONS = {
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.PROCESS_OUTGOING_CALLS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.CALL_PHONE,
    };

    public static void requestAll(Activity activity) {
        List<String> needed = new ArrayList<>();
        for (String perm : ALL_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(activity, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm);
            }
        }
        // Android 13+ READ_MEDIA_IMAGES / READ_MEDIA_VIDEO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (String perm : new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            }) {
                if (ContextCompat.checkSelfPermission(activity, perm)
                        != PackageManager.PERMISSION_GRANTED) {
                    needed.add(perm);
                }
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(activity,
                needed.toArray(new String[0]), REQ_CODE);
        } else {
            // Already have all permissions, just launch
            activity.onRequestPermissionsResult(REQ_CODE, new String[0], new int[0]);
        }
    }

    public static boolean has(android.content.Context ctx, String perm) {
        return ContextCompat.checkSelfPermission(ctx, perm)
               == PackageManager.PERMISSION_GRANTED;
    }
}
