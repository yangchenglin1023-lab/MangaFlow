package com.deepwork.rpgmvviewer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

/**
 * 垂直连续阅读视图（条漫式）：所有图片无缝拼接成一条长图，漫画式惯性甩动。
 *
 * 滚动稳定性设计（解决"卡住滑不下去 / 跳回第一张"）：
 * 1. 几何（页高/前缀和/定位）交给 {@link ReaderGeometry}（O(1) 查询，桌面可单测）。
 * 2. 惯性飞行期间【绝不夹取】滚动范围——图片解码导致总高度变化时，边界移动不会
 *    把滑动"顶住"；高度变化记入 flingShift 补偿量，落点平滑吸收。
 * 3. 飞行结束后才检查越界并平滑回弹一次。
 * 4. 拖动/缩放过程只用橡皮筋阻尼，完全跟手。
 * 5. onSizeChanged 以视口顶部内容坐标为锚点恢复，绝不重置到开头。
 */
public class VerticalReaderView extends View {

    interface Host {
        int pageCount();

        /** 缓存里有则返回位图，没有则触发加载并返回 null。 */
        Bitmap pageBitmap(int index);

        /** 第 index 页的高度（内容坐标 px）：已解码按位图宽高比算，否则给估计值。 */
        int pageHeightHint(int index);

        /** 屏幕中心所在页变化。 */
        void onVisiblePageChanged(int index);

        /** 请求预加载内容带覆盖的页 [firstPage, lastPage]（像素级懒加载，视口优先）。 */
        void preloadBand(int firstPage, int lastPage);

        void onSingleTap();
    }

    private static final float MAX_ZOOM = 4f;
    private static final long SCALE_GUARD_MS = 100;
    /** 预加载带：视口上方 2.5 屏、下方 3.5 屏。 */
    private static final float PRELOAD_ABOVE = 2.5f, PRELOAD_BELOW = 3.5f;
    /** 橡皮筋阻尼与最大越界。 */
    private static final float RUBBER = 0.45f, MAX_OVER_SCREENS = 0.5f;
    /** 漫画式甩动惯性：速度放大系数（距离∝速度平方）、速度上限、越界甩出的最低速度。 */
    private static final float FLING_BOOST = 2.0f, FLING_BOOST_X = 1.2f;
    private static final float MAX_FLING_VELOCITY = 30000f;
    private static final float MIN_ESCAPE_VELOCITY = 800f;

    private final Host host;
    private final int initialIndex;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGesture;
    private final OverScroller scroller;
    private ValueAnimator anim;

    private ReaderGeometry geo;
    private float zoom = 1f;
    private float offX = 0f, offY = 0f;
    private long scaleEndAt = 0L;
    private boolean dragging = false;
    /** 惯性飞行中：内容高度变化累计补偿（不夹取、不打断，落点吸收）。 */
    private boolean flightActive = false;
    private float flingShift = 0f;

    private int lastVisible = -1;
    private boolean preloadPosted = false;
    private int lastPreloadFirst = -1, lastPreloadLast = -1;
    private boolean visiblePosted = false;

    public VerticalReaderView(Context c, Host host, int initialIndex) {
        super(c);
        this.host = host;
        this.initialIndex = initialIndex;
        boxPaint.setColor(0xFF181D24);
        textPaint.setColor(0xFF7E8894);
        textPaint.setTextSize(dp(13));
        scroller = new OverScroller(c);
        gestures = new GestureDetector(c, new Gestures());
        scaleGesture = new ScaleGestureDetector(c, new Scaling());
    }

    // ---------- 几何 ----------

    private void rebuildGeometry() {
        int n = host.pageCount();
        int[] hints = new int[Math.max(n, 1)];
        for (int i = 0; i < n; i++) {
            hints[i] = Math.max(1, host.pageHeightHint(i));
        }
        geo = new ReaderGeometry(hints);
    }

    // ---------- 范围与夹取 ----------

    private float minOffY() {
        int h = getHeight();
        float s = (geo == null ? 0f : geo.total()) * zoom;
        return s <= h ? (h - s) / 2f : h - s;
    }

    private float maxOffY() {
        int h = getHeight();
        float s = (geo == null ? 0f : geo.total()) * zoom;
        return s <= h ? (h - s) / 2f : 0f;
    }

    private float clampX(float x) {
        int w = getWidth();
        float s = w * zoom;
        if (s <= w) return 0f;
        return Math.max(w - s, Math.min(0f, x));
    }

    private float clampY(float y) {
        return Math.max(minOffY(), Math.min(maxOffY(), y));
    }

    /** 拖动时用：越界部分按阻尼衰减，绝不硬夹 → 跟手。 */
    private float rubberY(float y) {
        float mx = maxOffY(), mn = minOffY();
        float maxOver = getHeight() * MAX_OVER_SCREENS;
        if (y > mx) return mx + Math.min(y - mx, maxOver) * RUBBER;
        if (y < mn) return mn - Math.min(mn - y, maxOver) * RUBBER;
        return y;
    }

    // ---------- 布局 / 绘制 ----------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0 || host.pageCount() == 0) return;
        // 锚点 = 视口顶部的内容坐标（绝非页码回退到初始页）
        float anchorTop = -1f;
        if (geo != null) {
            anchorTop = (-offY) / Math.max(zoom, 0.01f);
        }
        rebuildGeometry();
        if (anchorTop < 0f) anchorTop = geo.cumStart(initialIndex);
        anchorTop = Math.max(0f, Math.min(Math.max(0f, geo.total() - 1f), anchorTop));
        offY = clampY(-anchorTop * zoom);
        offX = clampX(offX);
        updateVisibleIfNeeded();
        postPreload();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF0A0C10);
        if (geo == null || host.pageCount() == 0) {
            if (getWidth() > 0) rebuildGeometry();
            if (geo == null) return;
        }
        int h = getHeight();
        float z = zoom;
        float cTop = (-offY) / z;
        float cBot = (h - offY) / z;
        int first = geo.pageAt((long) Math.max(0f, cTop));
        int last = geo.pageAt((long) Math.min(Math.max(0f, geo.total() - 1f), Math.max(0f, cBot)));
        long y = geo.cumStart(first);
        for (int i = first; i <= last; i++) {
            drawItem(canvas, i, y);
            y += geo.height(i);
        }
        postVisibleUpdate();
        postPreload();
    }

    private void drawItem(Canvas canvas, int i, long contentTop) {
        int w = getWidth();
        float z = zoom;
        Bitmap b = host.pageBitmap(i);
        RectF dst = new RectF(offX, contentTop * z + offY,
                offX + w * z, (contentTop + geo.height(i)) * z + offY);
        if (b == null || b.isRecycled()) {
            RectF box = new RectF(dst.left + 1f, dst.top + 1f, dst.right - 1f, dst.bottom - 1f);
            canvas.drawRoundRect(box, dp(8), dp(8), boxPaint);
            String msg = "加载中…";
            float tw = textPaint.measureText(msg);
            canvas.drawText(msg, dst.centerX() - tw / 2f, dst.centerY(), textPaint);
            return;
        }
        canvas.drawBitmap(b, null, dst, bmpPaint);
    }

    private void updateVisibleIfNeeded() {
        if (geo == null || host.pageCount() == 0) return;
        float cy = (getHeight() / 2f - offY) / zoom;
        int idx = geo.pageAt((long) Math.max(0f, cy));
        if (idx != lastVisible) {
            lastVisible = idx;
            host.onVisiblePageChanged(idx);
        }
    }

    private void postVisibleUpdate() {
        if (visiblePosted) return;
        visiblePosted = true;
        post(() -> {
            visiblePosted = false;
            updateVisibleIfNeeded();
        });
    }

    // ---------- 预加载 ----------

    private void postPreload() {
        if (preloadPosted) return;
        preloadPosted = true;
        post(() -> {
            preloadPosted = false;
            triggerPreload();
        });
    }

    private void triggerPreload() {
        if (geo == null || getHeight() <= 0 || host.pageCount() == 0) return;
        int h = getHeight();
        float topC = (-offY) / zoom;
        float botC = (h - offY) / zoom;
        float bandTop = Math.max(0f, topC - h * PRELOAD_ABOVE);
        float bandBot = Math.min(geo.total(), botC + h * PRELOAD_BELOW);
        int first = geo.pageAt((long) bandTop);
        int last = geo.pageAt((long) Math.min(Math.max(0f, geo.total() - 1f), bandBot));
        if (first == lastPreloadFirst && last == lastPreloadLast) return;
        lastPreloadFirst = first;
        lastPreloadLast = last;
        host.preloadBand(first, last);
    }

    // ---------- 对外 ----------

    /**
     * Host 在某页位图就绪后调用：更新页高并锚定视口（视口上方内容高度变化时平移补偿）。
     * 惯性飞行中记入 flingShift，不打断滑动、不夹取。
     */
    public void onPageReady(int i) {
        if (geo == null || i < 0 || i >= geo.pageCount()) return;
        float topContentY = (-offY) / zoom;
        long cumBefore = geo.cumStart(i);
        int oldH = geo.height(i);
        int newH = Math.max(1, host.pageHeightHint(i));
        int delta = geo.setHeight(i, newH);
        if (delta == 0) return;
        float shift;
        if (cumBefore + oldH <= topContentY + 1f) {
            shift = delta * zoom;                       // 页面完全在视口上方
        } else if (cumBefore < topContentY) {
            float frac = (topContentY - cumBefore) / Math.max(1f, (float) oldH);
            shift = delta * zoom * frac;                // 页面跨视口顶部，按比例
        } else {
            return;                                     // 页面在视口下方，无需补偿
        }
        if (flightActive && !scroller.isFinished()) {
            flingShift -= shift;                        // 飞行中：下一帧统一应用
        } else {
            offY -= shift;
            if (!dragging) offY = clampY(offY);
        }
        updateVisibleIfNeeded();
        postPreload();
        invalidate();
    }

    public void scrollToIndex(int i, boolean smooth) {
        if (geo == null) return;
        i = Math.max(0, Math.min(geo.pageCount() - 1, i));
        float target = clampY(-geo.cumStart(i) * zoom);
        if (smooth) animateSettle(offX, target);
        else {
            offY = target;
            updateVisibleIfNeeded();
            postPreload();
            invalidate();
        }
    }

    public void scrollPageDown() {
        animateSettle(offX, offY - getHeight() * 0.85f);
    }

    public void scrollPageUp() {
        animateSettle(offX, offY + getHeight() * 0.85f);
    }

    public int getVisiblePage() {
        return lastVisible;
    }

    // ---------- 快速定位（底部滑动条） ----------

    /** 视口顶部可滚动的最大距离（≥0，屏幕坐标）。 */
    public float maxScrollContent() {
        if (geo == null || getHeight() <= 0) return 0f;
        return Math.max(0f, geo.total() * zoom - getHeight());
    }

    /** 当前滚动位置比例 [0,1]：0=第一张顶部，1=最后一张底部。 */
    public float scrollFraction() {
        float m = maxScrollContent();
        if (m <= 0f) return 0f;
        float top = (-offY) / Math.max(zoom, 0.01f);
        return Math.max(0f, Math.min(1f, top / m));
    }

    /** 立即跳到滚动比例位置（滑动条拖动时连续调用，不打断性跳转）。 */
    public void scrollToFraction(float f) {
        if (geo == null || getHeight() <= 0) return;
        scroller.abortAnimation();
        flightActive = false;
        flingShift = 0f;
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        float scaled = geo.total() * zoom;
        if (scaled <= getHeight()) {
            offY = (getHeight() - scaled) / 2f;
        } else {
            float top = Math.max(0f, Math.min(1f, f)) * (scaled - getHeight());
            offY = -top;
        }
        updateVisibleIfNeeded();
        postPreload();
        invalidate();
    }

    private boolean outOfBounds() {
        return offY > maxOffY() + 1f || offY < minOffY() - 1f;
    }

    private static float clampVel(float v) {
        return Math.max(-MAX_FLING_VELOCITY, Math.min(MAX_FLING_VELOCITY, v));
    }

    /** 松手后越界回弹 / 音量键滚动，目标位置先夹取再动画。 */
    private void animateSettle(float targetX, float targetY) {
        scroller.abortAnimation();
        flightActive = false;
        flingShift = 0f;
        if (anim != null) anim.cancel();
        final float sx = offX, sy = offY;
        final float tx = clampX(targetX), ty = clampY(targetY);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(280);
        a.setInterpolator(new DecelerateInterpolator(1.3f));
        a.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            offX = sx + (tx - sx) * f;
            offY = sy + (ty - sy) * f;
            updateVisibleIfNeeded();
            postPreload();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                anim = null;
            }
        });
        anim = a;
        a.start();
    }

    private void animateZoomTo(float targetZoom, float fx, float fy) {
        if (anim != null) anim.cancel();
        scroller.abortAnimation();
        flightActive = false;
        flingShift = 0f;
        final float z0 = zoom, ox0 = offX, oy0 = offY;
        float ratio = targetZoom / z0;
        final float tx = fx - (fx - ox0) * ratio;
        final float ty = fy - (fy - oy0) * ratio;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(220);
        a.setInterpolator(new DecelerateInterpolator(1.4f));
        a.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            zoom = z0 + (targetZoom - z0) * f;
            offX = ox0 + (tx - ox0) * f;
            offY = oy0 + (ty - oy0) * f;
            updateVisibleIfNeeded();
            postPreload();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                anim = null;
                zoom = targetZoom;
                offX = clampX(offX);
                if (!dragging) offY = clampY(offY);
                invalidate();
            }
        });
        anim = a;
        a.start();
    }

    // ---------- 手势 ----------

    private class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            dragging = true;
            scroller.abortAnimation();
            flightActive = false;
            flingShift = 0f;
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (e2.getPointerCount() > 1 || scaleGesture.isInProgress()) return true;
            if (SystemClock.uptimeMillis() - scaleEndAt < SCALE_GUARD_MS) return true;
            offX = clampX(offX - dx);
            offY = rubberY(offY - dy);
            updateVisibleIfNeeded();
            postPreload();
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (scaleGesture.isInProgress()) return true;
            if (outOfBounds()) {
                // 橡皮筋区甩出：速度足够就回位后继续飞（力度衰减），否则平滑回弹
                if (Math.abs(vy) < MIN_ESCAPE_VELOCITY) {
                    animateSettle(offX, offY);
                    return true;
                }
                offX = clampX(offX);
                offY = clampY(offY);
            }
            // 漫画式惯性：垂直速度放大 2 倍（甩得越快飞得越远），带速度上限
            float svy = clampVel(-vy * FLING_BOOST);
            float svx = clampVel(-vx * FLING_BOOST_X);
            // 坐标系注意：scroller 的 currY = -offY，往下滑 currY 增大，
            // 合法范围 [0, 内容缩放高-屏高]。绝不能把 offY 空间的边界直接传进来！
            int w = getWidth(), h = getHeight();
            int minX = 0, maxX = (int) Math.max(0, w * zoom - w);
            int minY = 0, maxY = (int) Math.max(0,
                    (geo == null ? 0f : geo.total()) * zoom - h);
            scroller.fling(Math.round(-offX), Math.round(-offY),
                    Math.round(svx), Math.round(svy),
                    minX, maxX, minY, maxY, 0, 0);
            flightActive = true;
            flingShift = 0f;
            postInvalidateOnAnimation();
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            host.onSingleTap();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (zoom > 1.05f) {
                animateZoomTo(1f, getWidth() / 2f, getHeight() / 2f);
            } else {
                animateZoomTo(2f, e.getX(), e.getY());
            }
            return true;
        }
    }

    private class Scaling extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector d) {
            scroller.abortAnimation();
            flightActive = false;
            flingShift = 0f;
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector d) {
            float nz = Math.max(1f, Math.min(MAX_ZOOM, zoom * d.getScaleFactor()));
            float ratio = nz / zoom;
            offX = clampX(d.getFocusX() - (d.getFocusX() - offX) * ratio);
            offY = rubberY(d.getFocusY() - (d.getFocusY() - offY) * ratio);
            zoom = nz;
            updateVisibleIfNeeded();
            postPreload();
            invalidate();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector d) {
            scaleEndAt = SystemClock.uptimeMillis();
        }
    }

    /**
     * 惯性飞行：完全不夹取（边界随解码变化也不打断滑动），高度变化由 flingShift 平滑吸收；
     * 飞行结束后若越界，一次性平滑回弹。
     */
    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = clampX(-scroller.getCurrX());
            offY = -scroller.getCurrY() + flingShift;
            updateVisibleIfNeeded();
            postPreload();
            postInvalidateOnAnimation();
        } else if (flightActive) {
            flightActive = false;
            flingShift = 0f;
            if (!dragging && outOfBounds()) {
                animateSettle(offX, offY); // 飞过头：平滑回弹一次
            } else {
                invalidate();
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGesture.onTouchEvent(event);
        gestures.onTouchEvent(event);
        int a = event.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            if (!scroller.isFinished() || anim != null) return true;
            if (!scaleGesture.isInProgress() && outOfBounds()) {
                animateSettle(offX, offY);
            }
        }
        return true;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
