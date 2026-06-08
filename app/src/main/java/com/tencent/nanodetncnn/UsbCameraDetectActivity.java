package com.tencent.nanodetncnn;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * USB / 外接摄像头识别分支。
 *
 * 说明：
 * - 这版走 Android Camera2 的 EXTERNAL camera。
 * - 如果你的 USB 摄像头被系统识别为 Camera2 外接摄像头，就可以直接作为识别输入。
 * - 如果厂商摄像头只被专用 App 私有驱动打开，不暴露给 Camera2，则需要另做 libuvc / UVCCamera 分支。
 */
public class UsbCameraDetectActivity extends Activity {
    public static final String EXTRA_MODEL_ID = "modelId";
    public static final String EXTRA_CPU_GPU = "cpuGpu";

    private static final int REQUEST_CAMERA = 3001;
    private static final long INFER_INTERVAL_MS = 220L;
    private static final int MAX_IMAGE_WIDTH = 640;

    private final NanoDetNcnn detector = new NanoDetNcnn();

    private TextureView textureView;
    private DetectionOverlayView overlayView;
    private TextView statusText;

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private ImageReader imageReader;

    private Size captureSize;
    private String cameraId;
    private boolean externalCameraSelected;
    private boolean detecting;
    private long lastInferTime;

    private int modelId = 3; // ELite0_320
    private int cpuGpu = 0;  // CPU

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            startUsbCameraIfReady();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCameraSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            setStatus("USB摄像头已断开");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            setStatus("摄像头打开失败 error=" + error);
        }
    };

    private final ImageReader.OnImageAvailableListener imageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            handleCameraImage(reader);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        setContentView(R.layout.usb_camera_detect);

        textureView = (TextureView) findViewById(R.id.usbTextureView);
        overlayView = (DetectionOverlayView) findViewById(R.id.usbOverlayView);
        statusText = (TextView) findViewById(R.id.usbStatusText);

        Button close = (Button) findViewById(R.id.buttonCloseUsbCamera);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        modelId = getIntent().getIntExtra(EXTRA_MODEL_ID, 3);
        cpuGpu = getIntent().getIntExtra(EXTRA_CPU_GPU, 0);
        if ("armeabi-v7a".equals(Build.CPU_ABI)) {
            cpuGpu = 0;
        }

        boolean loaded = detector.loadModel(getAssets(), modelId, cpuGpu);
        if (!loaded) {
            Toast.makeText(this, "NanoDet 模型加载失败", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        textureView.setSurfaceTextureListener(textureListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCameraThread();
        if (textureView != null && textureView.isAvailable()) {
            startUsbCameraIfReady();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (overlayView != null) {
            overlayView.clear();
        }
        super.onDestroy();
    }

    private void startCameraThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("usb-camera-detect");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join(600L);
            } catch (InterruptedException ignored) {
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void startUsbCameraIfReady() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }

        if (cameraHandler == null) {
            startCameraThread();
        }

        openPreferredCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startUsbCameraIfReady();
            } else {
                Toast.makeText(this, "需要相机权限才能读取USB摄像头", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private void openPreferredCamera() {
        if (cameraDevice != null) {
            return;
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            setStatus("无法获取 CameraManager");
            return;
        }

        try {
            CameraChoice choice = chooseCamera(manager);
            if (choice == null) {
                setStatus("未找到可用摄像头");
                return;
            }

            cameraId = choice.cameraId;
            captureSize = choice.size;
            externalCameraSelected = choice.external;

            String type = externalCameraSelected ? "USB/外接摄像头" : "默认摄像头";
            setStatus("正在打开 " + type + "  id=" + cameraId + "  "
                    + captureSize.getWidth() + "x" + captureSize.getHeight());

            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (Throwable t) {
            t.printStackTrace();
            setStatus("打开摄像头异常：" + t.getMessage());
        }
    }

    private CameraChoice chooseCamera(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        List<CameraChoice> fallback = new ArrayList<CameraChoice>();

        for (String id : ids) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                continue;
            }

            Size[] yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (yuvSizes == null || yuvSizes.length == 0) {
                continue;
            }

            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            boolean isExternal = facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL;
            boolean isFront = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;

            CameraChoice choice = new CameraChoice();
            choice.cameraId = id;
            choice.size = chooseCaptureSize(yuvSizes);
            choice.external = isExternal;

            if (isExternal) {
                return choice;
            }
            if (!isFront) {
                fallback.add(choice);
            }
        }

        if (!fallback.isEmpty()) {
            return fallback.get(0);
        }

        for (String id : ids) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                continue;
            }
            Size[] yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (yuvSizes == null || yuvSizes.length == 0) {
                continue;
            }
            CameraChoice choice = new CameraChoice();
            choice.cameraId = id;
            choice.size = chooseCaptureSize(yuvSizes);
            choice.external = false;
            return choice;
        }

        return null;
    }

    private Size chooseCaptureSize(Size[] sizes) {
        List<Size> list = new ArrayList<Size>(Arrays.asList(sizes));
        Collections.sort(list, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                int aw = Math.abs(a.getWidth() - MAX_IMAGE_WIDTH);
                int bw = Math.abs(b.getWidth() - MAX_IMAGE_WIDTH);
                if (aw != bw) return aw - bw;
                int aa = Math.abs((a.getWidth() * 9) - (a.getHeight() * 16));
                int ba = Math.abs((b.getWidth() * 9) - (b.getHeight() * 16));
                return aa - ba;
            }
        });

        for (Size s : list) {
            if (s.getWidth() <= MAX_IMAGE_WIDTH && s.getWidth() >= 320) {
                return s;
            }
        }
        return list.get(0);
    }

    private void createCameraSession() {
        if (cameraDevice == null || textureView == null || !textureView.isAvailable() || captureSize == null) {
            return;
        }

        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) {
                return;
            }
            texture.setDefaultBufferSize(captureSize.getWidth(), captureSize.getHeight());
            Surface previewSurface = new Surface(texture);

            imageReader = ImageReader.newInstance(
                    captureSize.getWidth(),
                    captureSize.getHeight(),
                    ImageFormat.YUV_420_888,
                    2
            );
            imageReader.setOnImageAvailableListener(imageListener, cameraHandler);

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(
                                        captureRequestBuilder.build(),
                                        null,
                                        cameraHandler
                                );
                                String type = externalCameraSelected ? "USB/外接摄像头" : "默认摄像头";
                                setStatus(type + "识别中  " + captureSize.getWidth() + "x" + captureSize.getHeight());
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                                setStatus("启动摄像头预览失败：" + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            setStatus("摄像头会话配置失败");
                        }
                    },
                    cameraHandler
            );
        } catch (Throwable t) {
            t.printStackTrace();
            setStatus("创建摄像头会话异常：" + t.getMessage());
        }
    }

    private void handleCameraImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }

            long now = SystemClock.uptimeMillis();
            if (detecting || now - lastInferTime < INFER_INTERVAL_MS) {
                return;
            }
            detecting = true;
            lastInferTime = now;

            Bitmap bitmap = yuv420ImageToBitmap(image);
            if (bitmap == null) {
                detecting = false;
                return;
            }

            final float[] result = detector.detectBitmap(bitmap);
            bitmap.recycle();

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (overlayView != null) {
                        overlayView.updateDetections(result);
                    }
                }
            });
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (image != null) {
                image.close();
            }
            detecting = false;
        }
    }

    private Bitmap yuv420ImageToBitmap(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = yuv420ToNv21(image);
        if (nv21 == null) {
            return null;
        }

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean ok = yuvImage.compressToJpeg(new Rect(0, 0, width, height), 75, out);
        if (!ok) {
            return null;
        }
        byte[] jpeg = out.toByteArray();
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }

    private byte[] yuv420ToNv21(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) {
            return null;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = ySize / 2;
        byte[] nv21 = new byte[ySize + uvSize];

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        int pos = 0;
        for (int row = 0; row < height; row++) {
            int rowOffset = row * yRowStride;
            for (int col = 0; col < width; col++) {
                nv21[pos++] = yBuffer.get(rowOffset + col * yPixelStride);
            }
        }

        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        pos = ySize;
        for (int row = 0; row < chromaHeight; row++) {
            int uRowOffset = row * uRowStride;
            int vRowOffset = row * vRowStride;
            for (int col = 0; col < chromaWidth; col++) {
                nv21[pos++] = vBuffer.get(vRowOffset + col * vPixelStride);
                nv21[pos++] = uBuffer.get(uRowOffset + col * uPixelStride);
            }
        }

        return nv21;
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (overlayView != null) {
                overlayView.clear();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void setStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (statusText != null) {
                    statusText.setText(text);
                }
            }
        });
    }

    private static class CameraChoice {
        String cameraId;
        Size size;
        boolean external;
    }
}
