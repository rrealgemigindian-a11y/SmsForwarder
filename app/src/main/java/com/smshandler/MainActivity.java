package com.smshandler;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Intent permIntent = new Intent(this, PermissionActivity.class);
        startActivity(permIntent);
        
        new Handler().postDelayed(this::finish, 200);
    }
}
