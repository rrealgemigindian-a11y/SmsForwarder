package com.kasari.update;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQUEST_DEVICE_ADMIN = 1001;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private TextView statusText;
    private Button enableButton;
    private Button lockButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, DeviceAdminReceiver.class);
        statusText = findViewById(R.id.status_text);
        enableButton = findViewById(R.id.enable_admin_button);
        lockButton = findViewById(R.id.lock_device_button);

        enableButton.setOnClickListener(view -> requestDeviceAdmin());
        lockButton.setOnClickListener(view -> lockDevice());
        refreshControls();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (devicePolicyManager != null) {
            refreshControls();
        }
    }

    private void requestDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            getString(R.string.device_admin_explanation)
        );
        startActivityForResult(intent, REQUEST_DEVICE_ADMIN);
    }

    private void lockDevice() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            statusText.setText(R.string.admin_required_status);
            return;
        }

        devicePolicyManager.lockNow();
    }

    private void refreshControls() {
        boolean adminActive = devicePolicyManager.isAdminActive(adminComponent);
        statusText.setText(adminActive
            ? R.string.admin_enabled_status
            : R.string.admin_disabled_status);
        enableButton.setEnabled(!adminActive);
        lockButton.setEnabled(adminActive);
    }
}
