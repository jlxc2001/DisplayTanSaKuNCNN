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

public class DetectionOverlayView extends View {

    private NanoDetNcnn.Obj[] objects = new NanoDetNcnn.Obj[0];
    private int sourceWidth = 1;
    private int sourceHeight = 1;

    private final Object lock = new Object();

    // ===== 轻量动画 / 跟踪参数 =====
    private static final long FRAME_DELAY_MS = 50L;           // 20fps 左右，低配车机也比较稳
    private static final long ACQUIRE_ANIM_MS = 300L;         // 首次识别收缩锁定动画时长
    private static final long TRACK_STALE_MS = 700L;          // 目标短暂丢失后保留一会儿，减少闪烁
    private static final float MATCH_DISTANCE_FACTOR = 0.35f; // 目标匹配阈值，越大越不容易丢框
    private static final float TRACK_THRESHOLD = 0.60f;
    private static final float LOCKED_THRESHOLD = 0.80f;

    // ===== HUD 颜色分级 =====
    private static final int COLOR_TRACK = Color.rgb(110, 255, 170);   // TRACK：HUD 绿
    private static final int COLOR_LOCK = Color.rgb(255, 210, 90);     // LOCK：琥珀黄
    private static final int COLOR_LOCKED = Color.rgb(255, 90, 90);    // LOCKED：红色

    // ===== 画笔 =====
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ===== 简单目标跟踪，用来支持首次锁定动画和减少跳框 =====
    private final List<Track> tracks = new ArrayList<>();
    private long nextTrackId = 1L;

    private static class Track {
        long id;
        String label;
        float prob;
        RectF rect = new RectF();
        float centerX;
        float centerY;
        long bornTime;
        long lastSeenTime;
    }

    private enum LockLevel {
        TRACK, LOCK, LOCKED
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
        hudPaint.setStyle(Paint.Style.STROKE);
        hudPaint.setStrokeWidth(dp(2.2f));

        thinPaint.setStyle(Paint.Style.STROKE);
        thinPaint.setStrokeWidth(dp(1.2f));

        scanPaint.setStyle(Paint.Style.STROKE);
        scanPaint.setStrokeWidth(dp(1.5f));

        centerPaint.setStyle(Paint.Style.STROKE);
        centerPaint.setStrokeWidth(dp(1.5f));

        textPaint.setTextSize(dp(10f));
        textPaint.setFakeBoldText(true);

        textBoxPaint.setStyle(Paint.Style.STROKE);
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

    /**
     * 外部识别完成后调用。
     *
     * @param objs  NanoDet 识别结果
     * @param srcW  录屏帧宽度
     * @param srcH  录屏帧高度
     */
    public void setResults(NanoDetNcnn.Obj[] objs, int srcW, int srcH) {
        synchronized (lock) {
            this.objects = (objs != null) ? objs : new NanoDetNcnn.Obj[0];
            this.sourceWidth = Math.max(1, srcW);
            this.sourceHeight = Math.max(1, srcH);
            updateTracksLocked(this.objects, this.sourceWidth, this.sourceHeight);
        }
        invalidate();
    }

    private void updateTracksLocked(NanoDetNcnn.Obj[] objs, int srcW, int srcH) {
        long now = SystemClock.uptimeMillis();

        boolean[] used = new boolean[tracks.size()];
        List<Track> newOrUpdatedTracks = new ArrayList<>();

        float screenDiag = (float) Math.hypot(srcW, srcH);
        float matchMaxDist = screenDiag * MATCH_DISTANCE_FACTOR;

        for (NanoDetNcnn.Obj obj : objs) {
            if (obj == null) continue;

            RectF rect = new RectF(obj.x, obj.y, obj.x + obj.w, obj.y + obj.h);
            float cx = rect.centerX();
            float cy = rect.centerY();

            Track best = null;
            int bestIndex = -1;
            float bestScore = Float.MAX_VALUE;

            for (int i = 0; i < tracks.size(); i++) {
                if (used[i]) continue;
                Track t = tracks.get(i);

                // 标签相同优先匹配，避免 person / car 之间乱跳
                if (t.label != null && obj.label != null && !t.label.equals(obj.label)) {
                    continue;
                }

                float dx = t.centerX - cx;
                float dy = t.centerY - cy;
                float dist = (float) Math.hypot(dx, dy);

                if (dist < bestScore && dist < matchMaxDist) {
                    bestScore = dist;
                    best = t;
                    bestIndex = i;
                }
            }

            if (best != null) {
                used[bestIndex] = true;
                best.label = obj.label;
                best.prob = obj.prob;
                best.rect.set(rect);
                best.centerX = cx;
                best.centerY = cy;
                best.lastSeenTime = now;
                newOrUpdatedTracks.add(best);
            } else {
                Track t = new Track();
                t.id = nextTrackId++;
                t.label = obj.label;
                t.prob = obj.prob;
                t.rect.set(rect);
                t.centerX = cx;
                t.centerY = cy;
                t.bornTime = now;
                t.lastSeenTime = now;
                newOrUpdatedTracks.add(t);
            }
        }

        // 保留短暂丢失但未过期的目标，减少低帧率识别时的闪烁
        for (Track t : tracks) {
            if (now - t.lastSeenTime <= TRACK_STALE_MS && !newOrUpdatedTracks.contains(t)) {
                newOrUpdatedTracks.add(t);
            }
        }

        tracks.clear();
        tracks.addAll(newOrUpdatedTracks);

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

        List<Track> snapshot = new ArrayList<>();
        int srcW;
        int srcH;
        synchronized (lock) {
            snapshot.addAll(tracks);
            srcW = sourceWidth;
            srcH = sourceHeight;
        }

        drawHudHeader(canvas, snapshot.size());

        if (snapshot.isEmpty()) {
            return;
        }

        float scaleX = getWidth() / (float) srcW;
        float scaleY = getHeight() / (float) srcH;

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

            if (r.width() < dp(12) || r.height() < dp(12)) continue;

            LockLevel level = getLockLevel(t.prob);
            int color = getColorForLevel(level);

            applyColor(color);

            // A：首次识别时从外侧收缩到目标，模拟导弹捕获锁定
            float acquireProgress = Math.min(1f, (now - t.bornTime) / (float) ACQUIRE_ANIM_MS);
            RectF animatedRect = getAcquireAnimatedRect(r, acquireProgress);

            drawLockCorners(canvas, animatedRect, pulse);
            drawCenterReticle(canvas, animatedRect, pulse);
            drawScanLine(canvas, animatedRect, scanPhase);
            drawLabel(canvas, animatedRect, t.label, t.prob, level, t.id);
        }
    }

    private RectF getAcquireAnimatedRect(RectF target, float progress) {
        // 从 1.35 倍缩到 1.0 倍
        float scale = 1.35f - 0.35f * progress;

        float cx = target.centerX();
        float cy = target.centerY();
        float hw = target.width() * 0.5f * scale;
        float hh = target.height() * 0.5f * scale;

        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    private void applyColor(int color) {
        hudPaint.setColor(color);
        thinPaint.setColor(color);
        textPaint.setColor(color);
        textBoxPaint.setColor(color);
        centerPaint.setColor(color);
        scanPaint.setColor(Color.argb(180, Color.red(color), Color.green(color), Color.blue(color)));
    }

    private LockLevel getLockLevel(float prob) {
        if (prob >= LOCKED_THRESHOLD) return LockLevel.LOCKED;
        if (prob >= TRACK_THRESHOLD) return LockLevel.LOCK;
        return LockLevel.TRACK;
    }

    private int getColorForLevel(LockLevel level) {
        switch (level) {
            case LOCK:
                return COLOR_LOCK;
            case LOCKED:
                return COLOR_LOCKED;
            case TRACK:
            default:
                return COLOR_TRACK;
        }
    }

    private void drawHudHeader(Canvas canvas, int count) {
        int color = COLOR_TRACK;
        hudPaint.setColor(color);
        thinPaint.setColor(color);
        textPaint.setColor(color);

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

        float original = hudPaint.getStrokeWidth();
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

        // 两侧辅助线，增强战斗机 HUD 锁定感
        float midY = rr.centerY();
        canvas.drawLine(rr.left, midY, rr.left + dp(10), midY, thinPaint);
        canvas.drawLine(rr.right - dp(10), midY, rr.right, midY, thinPaint);

        // 上下短刻度线
        float midX = rr.centerX();
        canvas.drawLine(midX, rr.top, midX, rr.top + dp(8), thinPaint);
        canvas.drawLine(midX, rr.bottom - dp(8), midX, rr.bottom, thinPaint);

        hudPaint.setStrokeWidth(original);
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
        canvas.drawLine(r.left + dp(10), y - dp(4), r.right - dp(10), y - dp(4), thinPaint);
        canvas.drawLine(r.left + dp(16), y + dp(4), r.right - dp(16), y + dp(4), thinPaint);

        canvas.restore();
    }

    private void drawLabel(Canvas canvas, RectF r, String label, float prob, LockLevel level, long id) {
        String state;
        switch (level) {
            case LOCK:
                state = "LOCK";
                break;
            case LOCKED:
                state = "LOCKED";
                break;
            case TRACK:
            default:
                state = "TRACK";
                break;
        }

        String safeLabel = (label == null) ? "TARGET" : label.toUpperCase();
        String targetId = "TGT-" + String.format("%02d", Math.abs(id % 100));
        String tag = targetId + "  " + state + " " + safeLabel + " " + (int) (prob * 100) + "%";

        float padding = dp(4);
        float textW = textPaint.measureText(tag);
        float boxH = dp(16);

        float left = r.left;
        float top = Math.max(dp(4), r.top - boxH - dp(6));
        float right = left + textW + padding * 2;
        float bottom = top + boxH;

        // 连接线
        canvas.drawLine(r.left, r.top, r.left, top + boxH / 2f, thinPaint);
        canvas.drawLine(r.left, top + boxH / 2f, left + dp(8), top + boxH / 2f, thinPaint);

        // 标签框
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
