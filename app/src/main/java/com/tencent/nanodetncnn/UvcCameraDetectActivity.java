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
 * 真 UVC 摄像头识别入口。
 *
 * 工作流：
 * USB UVC Camera -> USBMonitor/libuvc -> UVCCamera preview + NV21 frame callback
 * -> NanoDetNcnn.detectBitmap() -> DetectionOverlayView HUD 框。
 *
 * 这条链路不依赖系统 Camera2 是否识别外接摄像头。
 */
public class UvcCameraDetectActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_MODEL_ID = "model_id";
    public static final String EXTRA_CPU_GPU = "cpu_gpu";

    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;
    private static final int PREVIEW_MODE_PRIMARY = UVCCamera.FRAME_FORMAT_MJPEG;
    private static final int PREVIEW_MODE_FALLBACK = UVCCamera.FRAME_FORMAT_YUYV;
    private static final int FRAME_PIXEL_FORMAT = UVCCamera.PIXEL_FORMAT_NV21;
    private static final long INFER_INTERVAL_MS = 220L;
    private static final int JPEG_QUALITY = 80;

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

    private NanoDetNcnn nanodet;
    private int modelId = 3;
    private int cpuGpu = 0;

    private final USBMonitor.OnDeviceConnectListener deviceListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatus("检测到UVC/USB设备，正在请求权限");
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
                    setStatus("USB权限已允许，正在打开UVC摄像头");
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

            final int frameSize = PREVIEW_WIDTH * PREVIEW_HEIGHT * 3 / 2;
            final byte[] nv21 = new byte[frameSize];
            ByteBuffer copy = frame.duplicate();
            copy.clear();
            int length = Math.min(copy.remaining(), frameSize);
            copy.get(nv21, 0, length);

            detectHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Bitmap bitmap = nv21ToBitmap(nv21, PREVIEW_WIDTH, PREVIEW_HEIGHT);
                        if (bitmap != null && nanodet != null) {
                            float[] result = nanodet.detectBitmap(bitmap);
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
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setStatus("识别帧处理失败");
                            }
                        });
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

        UsbDevice target = devices.get(0);
        setStatus("请求USB权限：" + target.getDeviceName());
        usbMonitor.requestPermission(target);
    }

    private synchronized void openCamera(final USBMonitor.UsbControlBlock ctrlBlock) {
        closeCamera();

        try {
            uvcCamera = new UVCCamera();
            uvcCamera.open(ctrlBlock);

            try {
                uvcCamera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_MODE_PRIMARY);
            } catch (IllegalArgumentException mjpegError) {
                uvcCamera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_MODE_FALLBACK);
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
                    setStatus("UVC识别中：" + PREVIEW_WIDTH + "x" + PREVIEW_HEIGHT + " / CPU优先");
                }
            });
        } catch (final Throwable t) {
            closeCamera();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatus("打开UVC失败：" + t.getClass().getSimpleName());
                    Toast.makeText(UvcCameraDetectActivity.this, "打开UVC失败，可能是不支持该分辨率/格式", Toast.LENGTH_LONG).show();
                }
            });
        }
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
