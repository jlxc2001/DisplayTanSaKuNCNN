package com.tencent.nanodetncnn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 战斗 HUD 风格识别框悬浮层。
 *
 * 适配 ScreenDetectService 当前调用方式：
 *   overlayView.updateDetections(float[] result);
 *   overlayView.clear();
 *
 * result 格式来自 NanoDetNcnn.detectBitmap(Bitmap)：
 *   float[]{srcW, srcH, label, prob, x, y, w, h, label, prob, x, y, w, h ...}
 */
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

    // ===== 低配置车机友好参数 =====
    private static final long FRAME_DELAY_MS = 50L;       // 20fps 动画，够帅且相对省资源
    private static final long ACQUIRE_ANIM_MS = 320L;     // 首次识别收缩锁定动画
    private static final long TRACK_STALE_MS = 700L;      // 短时间丢帧不立刻消失，避免闪烁
    private static final float MATCH_DISTANCE_FACTOR = 0.16f;

    // ===== 状态阈值 =====
    private static final float LOCK_THRESHOLD = 0.60f;
    private static final float LOCKED_THRESHOLD = 0.80f;

    // ===== HUD 颜色 =====
    private static final int COLOR_TRACK = Color.rgb(110, 255, 170);  // HUD 绿
    private static final int COLOR_LOCK = Color.rgb(255, 210, 90);    // 琥珀黄
    private static final int COLOR_LOCKED = Color.rgb(255, 90, 90);   // 锁定红

    private final Object lock = new Object();
    private final List<Track> tracks = new ArrayList<Track>();
    private long nextTrackId = 1L;

    private float sourceWidth = 1f;
    private float sourceHeight = 1f;

    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private enum LockLevel {
        TRACK, LOCK, LOCKED
    }

    private static class Track {
        long id;
        int label;
        float prob;
        RectF rect = new RectF();
        float centerX;
        float centerY;
        long bornTime;
        long lastSeenTime;
    }

    private static class Detection {
        int label;
        float prob;
        RectF rect;
        float centerX;
        float centerY;
    }

    private final Runnable animator = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, FRAME_DELAY_MS);
        }
    };

    public DetectionOverlayView(Context context) {
        super(context);
        init();
    }

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DetectionOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);

        hudPaint.setStyle(Paint.Style.STROKE);
        hudPaint.setStrokeWidth(dp(2.2f));
        hudPaint.setStrokeCap(Paint.Cap.SQUARE);

        thinPaint.setStyle(Paint.Style.STROKE);
        thinPaint.setStrokeWidth(dp(1.1f));
        thinPaint.setStrokeCap(Paint.Cap.SQUARE);

        scanPaint.setStyle(Paint.Style.STROKE);
        scanPaint.setStrokeWidth(dp(1.4f));
        scanPaint.setStrokeCap(Paint.Cap.SQUARE);

        centerPaint.setStyle(Paint.Style.STROKE);
        centerPaint.setStrokeWidth(dp(1.3f));
        centerPaint.setStrokeCap(Paint.Cap.SQUARE);

        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(dp(10f));
        textPaint.setFakeBoldText(true);

        textBoxPaint.setStyle(Paint.Style.STROKE);
        textBoxPaint.setStrokeWidth(dp(1f));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(animator);
        post(animator);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(animator);
        super.onDetachedFromWindow();
    }

    /**
     * ScreenDetectService 正在调用的方法，必须保留这个签名。
     */
    public void updateDetections(float[] result) {
        long now = SystemClock.uptimeMillis();

        synchronized (lock) {
            if (result == null || result.length < 8) {
                tracks.clear();
                invalidate();
                return;
            }

            sourceWidth = Math.max(1f, result[0]);
            sourceHeight = Math.max(1f, result[1]);

            List<Detection> detections = new ArrayList<Detection>();
            for (int i = 2; i + 5 < result.length; i += 6) {
                int label = (int) result[i];
                float prob = result[i + 1];
                float x = result[i + 2];
                float y = result[i + 3];
                float w = result[i + 4];
                float h = result[i + 5];

                if (w < 2f || h < 2f || prob <= 0f) {
                    continue;
                }

                Detection d = new Detection();
                d.label = label;
                d.prob = prob;
                d.rect = new RectF(x, y, x + w, y + h);
                d.centerX = d.rect.centerX();
                d.centerY = d.rect.centerY();
                detections.add(d);
            }

            updateTracksLocked(detections, now);
        }

        invalidate();
    }

    /**
     * ScreenDetectService 正在调用的方法，必须保留这个签名。
     */
    public void clear() {
        synchronized (lock) {
            tracks.clear();
        }
        invalidate();
    }

    private void updateTracksLocked(List<Detection> detections, long now) {
        boolean[] used = new boolean[tracks.size()];
        List<Track> newOrUpdated = new ArrayList<Track>();

        float diag = (float) Math.hypot(sourceWidth, sourceHeight);
        float matchMaxDist = Math.max(24f, diag * MATCH_DISTANCE_FACTOR);

        for (Detection d : detections) {
            Track best = null;
            int bestIndex = -1;
            float bestScore = Float.MAX_VALUE;

            for (int i = 0; i < tracks.size(); i++) {
                if (used[i]) {
                    continue;
                }

                Track t = tracks.get(i);
                if (t.label != d.label) {
                    continue;
                }

                float dx = t.centerX - d.centerX;
                float dy = t.centerY - d.centerY;
                float dist = (float) Math.hypot(dx, dy);

                if (dist < bestScore && dist < matchMaxDist) {
                    bestScore = dist;
                    best = t;
                    bestIndex = i;
                }
            }

            if (best != null) {
                used[bestIndex] = true;
                best.prob = d.prob;
                best.rect.set(d.rect);
                best.centerX = d.centerX;
                best.centerY = d.centerY;
                best.lastSeenTime = now;
                newOrUpdated.add(best);
            } else {
                Track t = new Track();
                t.id = nextTrackId++;
                t.label = d.label;
                t.prob = d.prob;
                t.rect.set(d.rect);
                t.centerX = d.centerX;
                t.centerY = d.centerY;
                t.bornTime = now;
                t.lastSeenTime = now;
                newOrUpdated.add(t);
            }
        }

        // 保留短暂丢失的目标，减少跳动/闪烁。
        for (Track t : tracks) {
            if (now - t.lastSeenTime <= TRACK_STALE_MS && !newOrUpdated.contains(t)) {
                newOrUpdated.add(t);
            }
        }

        tracks.clear();
        tracks.addAll(newOrUpdated);

        Iterator<Track> it = tracks.iterator();
        while (it.hasNext()) {
            Track t = it.next();
            if (now - t.lastSeenTime > TRACK_STALE_MS) {
                it.remove();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        List<Track> snapshot = new ArrayList<Track>();
        float srcW;
        float srcH;

        synchronized (lock) {
            snapshot.addAll(tracks);
            srcW = sourceWidth;
            srcH = sourceHeight;
        }

        drawHudHeader(canvas, snapshot.size());

        if (snapshot.isEmpty()) {
            return;
        }

        float scaleX = getWidth() / Math.max(1f, srcW);
        float scaleY = getHeight() / Math.max(1f, srcH);

        long now = SystemClock.uptimeMillis();
        float pulse = 0.90f + 0.10f * (float) Math.sin(now * 0.008f);
        float scanPhase = (now % 1200L) / 1200f;

        for (Track t : snapshot) {
            RectF r = new RectF(
                    t.rect.left * scaleX,
                    t.rect.top * scaleY,
                    t.rect.right * scaleX,
                    t.rect.bottom * scaleY
            );

            if (r.width() < dp(10f) || r.height() < dp(10f)) {
                continue;
            }

            LockLevel level = getLockLevel(t.prob);
            applyColor(getColorForLevel(level));

            float acquireProgress = Math.min(1f, (now - t.bornTime) / (float) ACQUIRE_ANIM_MS);
            acquireProgress = easeOutCubic(acquireProgress);
            RectF animatedRect = getAcquireAnimatedRect(r, acquireProgress);

            drawLockCorners(canvas, animatedRect, pulse);
            drawCenterReticle(canvas, animatedRect, pulse);
            drawScanLine(canvas, animatedRect, scanPhase);
            drawLabel(canvas, animatedRect, t, level);
        }
    }

    private float easeOutCubic(float x) {
        float p = 1f - x;
        return 1f - p * p * p;
    }

    private RectF getAcquireAnimatedRect(RectF target, float progress) {
        // 从 1.45 倍快速收缩到 1.0 倍，模拟导弹捕获目标。
        float scale = 1.45f - 0.45f * progress;
        float cx = target.centerX();
        float cy = target.centerY();
        float halfW = target.width() * 0.5f * scale;
        float halfH = target.height() * 0.5f * scale;
        return new RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
    }

    private LockLevel getLockLevel(float prob) {
        if (prob >= LOCKED_THRESHOLD) {
            return LockLevel.LOCKED;
        }
        if (prob >= LOCK_THRESHOLD) {
            return LockLevel.LOCK;
        }
        return LockLevel.TRACK;
    }

    private int getColorForLevel(LockLevel level) {
        if (level == LockLevel.LOCKED) {
            return COLOR_LOCKED;
        }
        if (level == LockLevel.LOCK) {
            return COLOR_LOCK;
        }
        return COLOR_TRACK;
    }

    private void applyColor(int color) {
        hudPaint.setColor(color);
        thinPaint.setColor(color);
        textPaint.setColor(color);
        textBoxPaint.setColor(color);
        centerPaint.setColor(color);
        scanPaint.setColor(Color.argb(180, Color.red(color), Color.green(color), Color.blue(color)));
    }

    private void drawHudHeader(Canvas canvas, int count) {
        hudPaint.setColor(COLOR_TRACK);
        thinPaint.setColor(COLOR_TRACK);
        textPaint.setColor(COLOR_TRACK);

        String text = "HUD TRACK  TARGETS:" + count;
        float x = dp(10f);
        float y = dp(18f);

        canvas.drawLine(dp(8f), dp(8f), dp(96f), dp(8f), thinPaint);
        canvas.drawLine(dp(8f), dp(8f), dp(8f), dp(25f), thinPaint);
        canvas.drawText(text, x, y, textPaint);
    }

    private void drawLockCorners(Canvas canvas, RectF r, float pulse) {
        float corner = Math.max(dp(14f), Math.min(r.width(), r.height()) * 0.22f);
        float inset = dp(1.5f);
        RectF rr = new RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset);

        float originalStroke = hudPaint.getStrokeWidth();
        hudPaint.setStrokeWidth(dp(2.2f) * pulse);

        // 左上
        canvas.drawLine(rr.left, rr.top, rr.left + corner, rr.top, hudPaint);
        canvas.drawLine(rr.left, rr.top, rr.left, rr.top + corner, hudPaint);

        // 右上
        canvas.drawLine(rr.right - corner, rr.top, rr.right, rr.top, hudPaint);
        canvas.drawLine(rr.right, rr.top, rr.right, rr.top + corner, hudPaint);

        // 左下
        canvas.drawLine(rr.left, rr.bottom - corner, rr.left, rr.bottom, hudPaint);
        canvas.drawLine(rr.left, rr.bottom, rr.left + corner, rr.bottom, hudPaint);

        // 右下
        canvas.drawLine(rr.right - corner, rr.bottom, rr.right, rr.bottom, hudPaint);
        canvas.drawLine(rr.right, rr.bottom - corner, rr.right, rr.bottom, hudPaint);

        // 两侧短线，增强锁定感
        float midY = rr.centerY();
        canvas.drawLine(rr.left, midY, rr.left + dp(10f), midY, thinPaint);
        canvas.drawLine(rr.right - dp(10f), midY, rr.right, midY, thinPaint);

        hudPaint.setStrokeWidth(originalStroke);
    }

    private void drawCenterReticle(Canvas canvas, RectF r, float pulse) {
        float cx = r.centerX();
        float cy = r.centerY();
        float radius = Math.min(r.width(), r.height()) * 0.08f;
        radius = Math.max(dp(5f), Math.min(dp(12f), radius));
        float arm = radius + dp(5f);

        canvas.drawCircle(cx, cy, radius * pulse, centerPaint);
        canvas.drawLine(cx - arm, cy, cx - radius, cy, centerPaint);
        canvas.drawLine(cx + radius, cy, cx + arm, cy, centerPaint);
        canvas.drawLine(cx, cy - arm, cx, cy - radius, centerPaint);
        canvas.drawLine(cx, cy + radius, cx, cy + arm, centerPaint);
    }

    private void drawScanLine(Canvas canvas, RectF r, float phase) {
        float y = r.top + r.height() * phase;

        canvas.save();
        canvas.clipRect(r);
        canvas.drawLine(r.left + dp(2f), y, r.right - dp(2f), y, scanPaint);
        canvas.drawLine(r.left + dp(10f), y - dp(4f), r.right - dp(10f), y - dp(4f), thinPaint);
        canvas.drawLine(r.left + dp(16f), y + dp(4f), r.right - dp(16f), y + dp(4f), thinPaint);
        canvas.restore();
    }

    private void drawLabel(Canvas canvas, RectF r, Track t, LockLevel level) {
        String state;
        if (level == LockLevel.LOCKED) {
            state = "LOCKED";
        } else if (level == LockLevel.LOCK) {
            state = "LOCK";
        } else {
            state = "TRACK";
        }

        String name = getClassName(t.label).toUpperCase();
        String tag = state + " TGT-" + t.id + " " + name + " " + Math.round(t.prob * 100f) + "%";

        float padding = dp(4f);
        float textW = textPaint.measureText(tag);
        float boxH = dp(16f);

        float left = r.left;
        float top = Math.max(dp(4f), r.top - boxH - dp(6f));
        float right = left + textW + padding * 2f;
        float bottom = top + boxH;

        // 避免文字超出右边屏幕
        if (right + dp(8f) > getWidth()) {
            float shift = right + dp(8f) - getWidth();
            left -= shift;
            right -= shift;
        }
        if (left < dp(2f)) {
            right += dp(2f) - left;
            left = dp(2f);
        }

        canvas.drawLine(r.left, r.top, r.left, top + boxH * 0.5f, thinPaint);
        canvas.drawLine(r.left, top + boxH * 0.5f, left + dp(8f), top + boxH * 0.5f, thinPaint);

        canvas.drawRect(left + dp(8f), top, right + dp(8f), bottom, textBoxPaint);
        canvas.drawText(tag, left + dp(8f) + padding, top + boxH - dp(4f), textPaint);
    }

    private String getClassName(int label) {
        if (label >= 0 && label < CLASS_NAMES.length) {
            return CLASS_NAMES[label];
        }
        return "target";
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
