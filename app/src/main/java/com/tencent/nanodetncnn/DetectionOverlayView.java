package com.tencent.nanodetncnn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class DetectionOverlayView extends View {
    private static final String[] CLASS_NAMES = {
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork",
            "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli",
            "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant",
            "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
            "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock",
            "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    };

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float[] detections = new float[0];
    private float sourceWidth = 1f;
    private float sourceHeight = 1f;

    public DetectionOverlayView(Context context) {
        super(context);
        init();
    }

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);
        boxPaint.setColor(Color.argb(230, 0, 255, 120));

        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(28f);
        textPaint.setColor(Color.WHITE);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(Color.argb(190, 0, 0, 0));
    }

    public void updateDetections(float[] result) {
        if (result == null || result.length < 2) {
            detections = new float[0];
            invalidate();
            return;
        }

        sourceWidth = Math.max(1f, result[0]);
        sourceHeight = Math.max(1f, result[1]);
        detections = result;
        invalidate();
    }

    public void clear() {
        detections = new float[0];
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (detections == null || detections.length < 8) {
            return;
        }

        float scaleX = getWidth() / sourceWidth;
        float scaleY = getHeight() / sourceHeight;

        for (int i = 2; i + 5 < detections.length; i += 6) {
            int label = (int) detections[i];
            float prob = detections[i + 1];
            float x = detections[i + 2] * scaleX;
            float y = detections[i + 3] * scaleY;
            float w = detections[i + 4] * scaleX;
            float h = detections[i + 5] * scaleY;

            rect.set(x, y, x + w, y + h);
            canvas.drawRect(rect, boxPaint);

            String name = label >= 0 && label < CLASS_NAMES.length ? CLASS_NAMES[label] : String.valueOf(label);
            String text = name + " " + Math.round(prob * 100f) + "%";
            float textWidth = textPaint.measureText(text);
            float textHeight = textPaint.getTextSize() + 8f;
            float bgLeft = x;
            float bgTop = Math.max(0f, y - textHeight);
            canvas.drawRect(bgLeft, bgTop, bgLeft + textWidth + 12f, bgTop + textHeight, bgPaint);
            canvas.drawText(text, bgLeft + 6f, bgTop + textPaint.getTextSize(), textPaint);
        }
    }
}
