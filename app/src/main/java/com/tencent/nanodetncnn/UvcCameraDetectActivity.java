package com.tencent.nanodetncnn;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * 真 UVC 摄像头识别入口 - 自动兼容分辨率/格式版本。
 *
 * 这版不再固定 640x480，而是依次尝试常见 UVC 输出组合：
 * MJPEG/YUYV + 640x480 / 320x240 / 800x600 / 1280x720 / 640x360 ...
 * 解决部分摄像头在固定 640x480 MJPEG/YUYV 下打开失败的问题。
 */
public class UvcCameraDetectActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_MODEL_ID = "model_id";
    public static final String EXTRA_CPU_GPU = "cpu_gpu";

    private static final long INFER_INTERVAL_MS = 220L;
    private static final int JPEG_QUALITY = 80;

    // 优先尝试低负载尺寸。部分车机/摄像头不支持某些尺寸，所以要自动轮询。
    private static final int[][] PREVIEW_SIZES = new int[][]{
            {640, 480},
            {320, 240},
            {800, 600},
            {1280, 720},
            {960, 720},
            {640, 360},
            {352, 288},
            {1920, 1080}
    };

    private static final int[] PREVIEW_MODES = new int[]{
            UVCCamera.FRAME_FORMAT_MJPEG,
            UVCCamera.FRAME_FORMAT_YUYV
    };

    private static final int FRAME_PIXEL_FORMAT = UVCCamera.PIXEL_FORMAT_NV21;

    private SurfaceView previewView;
    private DetectionOverlayView overlayView;
    private TextView statusText;

    private USBMonitor usbMonitor;
    private UVCCamera uvcCamera;
    private SurfaceHolder previewHolder;

    private HandlerThread detectThread;
    private Handler detectHandler;
    private volatile boolean detecting = false;
    private volatile boolean detectBusy = false;
    private long lastInferTime = 0L;

    private int activeWidth = 640;
    private int activeHeight = 480;
    private int activeMode = UVCCamera.FRAME_FORMAT_MJPEG;

    private NanoDetNcnn nanodet;
    private int modelId = 3;
    private int cpuGpu = 0;

    private final USBMonitor.OnDeviceConnectListener deviceListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatus("检测到USB设备，正在请求权限：" + device.getProductName());
                    Toast.makeText(UvcCameraDetectActivity.this, "检测到USB设备，正在请求权限", Toast.LENGTH_SHORT).show();
                }
            });
            if (usbMonitor != null) {
                usbMonitor.requestPermission(device);
            }
        }

        @Override
        public void onDettach(final UsbDevice device) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatus("USB设备已拔出");
                    Toast.makeText(UvcCameraDetectActivity.this, "USB设备已拔出", Toast.LENGTH_SHORT).show();
                }
            });
            closeCamera();
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatus("USB权限已允许，正在自动匹配UVC格式...");
                }
            });
            openCamera(ctrlBlock);
        }

        @Override
        public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {
            closeCamera();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatus("UVC摄像头已断开");
                }
            });
        }

        @Override
        public void onCancel(final UsbDevice device) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatus("USB授权被取消");
                    Toast.makeText(UvcCameraDetectActivity.this, "USB授权被取消", Toast.LENGTH_SHORT).show();
                }
            });
        }
    };

    private final IFrameCallback frameCallback = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {
            if (!detecting || detectHandler == null || frame == null) {
                return;
            }

            long now = SystemClock.uptimeMillis();
            if (detectBusy || now - lastInferTime < INFER_INTERVAL_MS) {
                return;
            }
            lastInferTime = now;
            detectBusy = true;

            final int w = activeWidth;
            final int h = activeHeight;
            final int frameSize = w * h * 3 / 2;
            final byte[] nv21 = new byte[frameSize];

            try {
                ByteBuffer copy = frame.duplicate();
                copy.clear();
                int length = Math.min(copy.remaining(), frameSize);
                copy.get(nv21, 0, length);
            } catch (Throwable t) {
                detectBusy = false;
                return;
            }

            detectHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Bitmap bitmap = nv21ToBitmap(nv21, w, h);
                        if (bitmap != null && nanodet != null) {
                            final float[] result = nanodet.detectBitmap(bitmap);
                            if (result != null) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        overlayView.updateDetections(result);
                                    }
                                });
                            }
                            bitmap.recycle();
                        }
                    } catch (Throwable t) {
                        // 避免低配车机因为偶发坏帧崩溃。
                    } finally {
                        detectBusy = false;
                    }
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uvc_camera_detect);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        modelId = getIntent().getIntExtra(EXTRA_MODEL_ID, 3);
        cpuGpu = getIntent().getIntExtra(EXTRA_CPU_GPU, 0);

        previewView = (SurfaceView) findViewById(R.id.uvcPreviewView);
        overlayView = (DetectionOverlayView) findViewById(R.id.uvcOverlayView);
        statusText = (TextView) findViewById(R.id.uvcStatusText);
        Button openButton = (Button) findViewById(R.id.buttonOpenUvc);
        Button closeButton = (Button) findViewById(R.id.buttonCloseUvc);

        previewHolder = previewView.getHolder();
        previewHolder.addCallback(this);

        nanodet = new NanoDetNcnn();
        boolean ok = nanodet.loadModel(getAssets(), modelId, cpuGpu);
        if (!ok) {
            Toast.makeText(this, "NanoDet模型加载失败", Toast.LENGTH_LONG).show();
        }

        detectThread = new HandlerThread("uvc-nanodet-detect");
        detectThread.start();
        detectHandler = new Handler(detectThread.getLooper());

        usbMonitor = new USBMonitor(this, deviceListener);

        openButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestFirstUsbCamera();
            }
        });

        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeCamera();
                overlayView.clear();
                setStatus("UVC摄像头已关闭");
            }
        });

        setStatus("UVC READY：插入摄像头后点打开UVC");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (usbMonitor != null) {
            usbMonitor.register();
        }
    }

    @Override
    protected void onStop() {
        closeCamera();
        if (usbMonitor != null) {
            usbMonitor.unregister();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        if (usbMonitor != null) {
            usbMonitor.destroy();
            usbMonitor = null;
        }
        if (detectThread != null) {
            detectThread.quitSafely();
            detectThread = null;
            detectHandler = null;
        }
        super.onDestroy();
    }

    private void requestFirstUsbCamera() {
        if (usbMonitor == null) {
            setStatus("USBMonitor未初始化");
            return;
        }

        List<UsbDevice> devices = usbMonitor.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            setStatus("未发现USB摄像头：请确认OTG/USB Host可用");
            Toast.makeText(this, "未发现USB设备", Toast.LENGTH_SHORT).show();
            return;
        }

        // 多数车机只插一个 UVC 摄像头。这里仍然选第一个，由 USB 权限弹窗显示设备名。
        UsbDevice target = devices.get(0);
        setStatus("请求USB权限：" + target.getDeviceName());
        usbMonitor.requestPermission(target);
    }

    private synchronized void openCamera(final USBMonitor.UsbControlBlock ctrlBlock) {
        closeCamera();

        try {
            uvcCamera = new UVCCamera();
            uvcCamera.open(ctrlBlock);

            String lastError = tryConfigurePreview(uvcCamera);
            if (lastError != null) {
                throw new IllegalArgumentException(lastError);
            }

            if (previewHolder != null) {
                uvcCamera.setPreviewDisplay(previewHolder);
            }
            uvcCamera.setFrameCallback(frameCallback, FRAME_PIXEL_FORMAT);
            uvcCamera.startPreview();
            detecting = true;

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String mode = activeMode == UVCCamera.FRAME_FORMAT_MJPEG ? "MJPEG" : "YUYV";
                    setStatus("UVC识别中：" + activeWidth + "x" + activeHeight + " / " + mode);
                    Toast.makeText(UvcCameraDetectActivity.this,
                            "UVC已打开：" + activeWidth + "x" + activeHeight + " " + mode,
                            Toast.LENGTH_SHORT).show();
                }
            });
        } catch (final Throwable t) {
            closeCamera();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String msg = t.getMessage();
                    if (msg == null || msg.length() == 0) {
                        msg = t.getClass().getSimpleName();
                    }
                    setStatus("打开UVC失败：" + msg);
                    Toast.makeText(UvcCameraDetectActivity.this, "打开UVC失败：已尝试多种分辨率/格式", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /**
     * 返回 null 表示成功，非 null 表示所有组合都失败，并携带最后一个错误。
     */
    private String tryConfigurePreview(UVCCamera camera) {
        String lastError = "unknown";

        for (int mode : PREVIEW_MODES) {
            for (int[] size : PREVIEW_SIZES) {
                try {
                    camera.setPreviewSize(size[0], size[1], mode);
                    activeWidth = size[0];
                    activeHeight = size[1];
                    activeMode = mode;
                    return null;
                } catch (Throwable t) {
                    String modeName = mode == UVCCamera.FRAME_FORMAT_MJPEG ? "MJPEG" : "YUYV";
                    String msg = t.getMessage();
                    if (msg == null) msg = t.getClass().getSimpleName();
                    lastError = size[0] + "x" + size[1] + " " + modeName + " -> " + msg;
                }
            }
        }

        return lastError;
    }

    private synchronized void closeCamera() {
        detecting = false;
        detectBusy = false;

        if (uvcCamera != null) {
            try {
                uvcCamera.setFrameCallback(null, 0);
            } catch (Throwable ignored) {
            }
            try {
                uvcCamera.stopPreview();
            } catch (Throwable ignored) {
            }
            try {
                uvcCamera.close();
            } catch (Throwable ignored) {
            }
            try {
                uvcCamera.destroy();
            } catch (Throwable ignored) {
            }
            uvcCamera = null;
        }
    }

    private Bitmap nv21ToBitmap(byte[] nv21, int width, int height) {
        YuvImage image = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean ok = image.compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, out);
        if (!ok) {
            return null;
        }
        byte[] jpeg = out.toByteArray();
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }

    private void setStatus(String text) {
        if (statusText != null) {
            statusText.setText(text);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        previewHolder = holder;
        if (uvcCamera != null) {
            try {
                uvcCamera.setPreviewDisplay(holder);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        previewHolder = holder;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        previewHolder = null;
    }
}
