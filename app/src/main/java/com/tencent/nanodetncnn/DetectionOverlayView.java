package com.tencent.nanodetncnn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public class DetectionOverlayView extends View {

    private NanoDetNcnn.Obj[] objects = new NanoDetNcnn.Obj[0];
    private int sourceWidth = 1;
    private int sourceHeight = 1;

    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable animator = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, 50); // 20fps 左右，比较省性能
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
        int hudColor = Color.rgb(110, 255, 170); // 偏战斗HUD的荧光绿

        hudPaint.setStyle(Paint.Style.STROKE);
        hudPaint.setColor(hudColor);
        hudPaint.setStrokeWidth(dp(2.2f));

        thinPaint.setStyle(Paint.Style.STROKE);
        thinPaint.setColor(hudColor);
        thinPaint.setStrokeWidth(dp(1.2f));

        scanPaint.setStyle(Paint.Style.STROKE);
        scanPaint.setColor(Color.argb(180, 110, 255, 170));
        scanPaint.setStrokeWidth(dp(1.5f));

        centerPaint.setStyle(Paint.Style.STROKE);
        centerPaint.setColor(hudColor);
        centerPaint.setStrokeWidth(dp(1.5f));

        textPaint.setColor(hudColor);
        textPaint.setTextSize(dp(10f));
        textPaint.setFakeBoldText(true);

        textBoxPaint.setStyle(Paint.Style.STROKE);
        textBoxPaint.setColor(hudColor);
        textBoxPaint.setStrokeWidth(dp(1f));

        setWillNotDraw(false);
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

    public synchronized void setResults(NanoDetNcnn.Obj[] objs, int srcW, int srcH) {
        if (objs == null) objs = new NanoDetNcnn.Obj[0];
        this.objects = objs;
        this.sourceWidth = Math.max(1, srcW);
        this.sourceHeight = Math.max(1, srcH);
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (objects == null || objects.length == 0) {
            drawHudHeader(canvas, 0);
            return;
        }

        float scaleX = getWidth() / (float) sourceWidth;
        float scaleY = getHeight() / (float) sourceHeight;

        long now = SystemClock.uptimeMillis();
        float pulse = 0.88f + 0.12f * (float) Math.sin(now * 0.008f);
        float scanPhase = (now % 1200L) / 1200f;

        drawHudHeader(canvas, objects.length);

        for (NanoDetNcnn.Obj obj : objects) {
            if (obj == null) continue;

            RectF r = new RectF(
                    obj.x * scaleX,
                    obj.y * scaleY,
                    (obj.x + obj.w) * scaleX,
                    (obj.y + obj.h) * scaleY
            );

            if (r.width() < dp(12) || r.height() < dp(12)) continue;

            drawLockCorners(canvas, r, pulse);
            drawCenterReticle(canvas, r, pulse);
            drawScanLine(canvas, r, scanPhase);
            drawLabel(canvas, r, obj.label, obj.prob);
        }
    }

    private void drawHudHeader(Canvas canvas, int count) {
        String text = "HUD TRACK  TARGETS:" + count;
        float x = dp(10);
        float y = dp(18);

        canvas.drawLine(dp(8), dp(8), dp(90), dp(8), thinPaint);
        canvas.drawLine(dp(8), dp(8), dp(8), dp(24), thinPaint);
        canvas.drawText(text, x, y, textPaint);
    }

    private void drawLockCorners(Canvas canvas, RectF r, float pulse) {
        float corner = Math.max(dp(14), Math.min(r.width(), r.height()) * 0.22f);
        float inset = dp(1.5f);

        RectF rr = new RectF(
                r.left + inset,
                r.top + inset,
                r.right - inset,
                r.bottom - inset
        );

        float w = hudPaint.getStrokeWidth();
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

        // 中间两条短辅助线，增强“锁定”感
        float midY = rr.centerY();
        canvas.drawLine(rr.left, midY, rr.left + dp(10), midY, thinPaint);
        canvas.drawLine(rr.right - dp(10), midY, rr.right, midY, thinPaint);

        hudPaint.setStrokeWidth(w);
    }

    private void drawCenterReticle(Canvas canvas, RectF r, float pulse) {
        float cx = r.centerX();
        float cy = r.centerY();
        float radius = Math.min(r.width(), r.height()) * 0.08f;
        radius = Math.max(dp(5), Math.min(dp(12), radius));

        float arm = radius + dp(5);

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
        canvas.drawLine(r.left + dp(2), y, r.right - dp(2), y, scanPaint);

        // 再加两条更短的辅助扫描线，稍微有一点“雷达扫过”的感觉
        canvas.drawLine(r.left + dp(10), y - dp(4), r.right - dp(10), y - dp(4), thinPaint);
        canvas.drawLine(r.left + dp(16), y + dp(4), r.right - dp(16), y + dp(4), thinPaint);
        canvas.restore();
    }

    private void drawLabel(Canvas canvas, RectF r, String label, float prob) {
        String tag = "LOCK " + label.toUpperCase() + " " + (int) (prob * 100) + "%";

        float padding = dp(4);
        float textW = textPaint.measureText(tag);
        float boxH = dp(16);

        float left = r.left;
        float top = Math.max(dp(4), r.top - boxH - dp(6));
        float right = left + textW + padding * 2;
        float bottom = top + boxH;

        // 连线
        canvas.drawLine(r.left, r.top, r.left, top + boxH / 2f, thinPaint);
        canvas.drawLine(r.left, top + boxH / 2f, left + dp(8), top + boxH / 2f, thinPaint);

        // 线框标签
        canvas.drawRect(left + dp(8), top, right + dp(8), bottom, textBoxPaint);

        // 文本
        float tx = left + dp(8) + padding;
        float ty = top + boxH - dp(4);
        canvas.drawText(tag, tx, ty, textPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
