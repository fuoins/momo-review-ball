package com.fuoins.momoreviewball;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

/**
 * 盖在墨墨自带悬浮球正上方的一层真实 View。
 *
 * <p>原来用的是 {@code View.getOverlay()}，但那个方案有两个坑：
 * <ul>
 *   <li>bounds 只在 {@code add()} 那一刻按控件宽高写一次，inflate 阶段是 0；</li>
 *   <li>实测 overlay 报的尺寸是 582×582，球控件实际只有 448×448，
 *       draw 出来的东西整个落在球外面，于是「球上什么都没变」。</li>
 * </ul>
 *
 * <p>改成兄弟 View 之后：绘制在球的父容器里、z 序在球之上，
 * 位置和缩放由 {@link ViewTreeObserver.OnPreDrawListener} 每帧对齐，
 * 拖动、回弹、按压缩放都跟得住，也不用再猜 overlay 的坐标系。
 */
final class BallCoverView extends View {

    private final View host;
    private final BallOverlay paint;
    private float lastLoggedAlpha = -1f;

    private BallCoverView(Context context, View host, BallOverlay paint) {
        super(context);
        this.host = host;
        this.paint = paint;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * 在球的父容器里挂一层覆盖 View。
     *
     * @return 挂上了返回该 View；父容器不可用（还没 attach 或不是 ViewGroup）返回 null
     */
    static BallCoverView attach(View host, BallOverlay paint) {
        try {
            ViewParent p = host.getParent();
            if (!(p instanceof ViewGroup)) return null;
            ViewGroup parent = (ViewGroup) p;

            BallCoverView cover = new BallCoverView(host.getContext(), host, paint);
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    Math.max(host.getWidth(), 1), Math.max(host.getHeight(), 1));
            parent.addView(cover, lp);
            cover.bringToFront();
            cover.syncGeometry();
            cover.registerPreDraw();
            host.getViewTreeObserver().addOnPreDrawListener(cover.preDraw);
            XposedLog.i("球面覆盖层已挂到 " + parent.getClass().getSimpleName()
                    + "，尺寸=" + host.getWidth() + "x" + host.getHeight());
            return cover;
        } catch (Throwable t) {
            XposedLog.w("挂载球面覆盖层失败", t);
            return null;
        }
    }

    private final ViewTreeObserver.OnPreDrawListener preDraw = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (!isAttachedToWindow() || host.getParent() == null) {
                detach();
                return true;
            }
            syncGeometry();
            return true;
        }
    };

    /** 让覆盖层和球完全重合：位置、尺寸、位移、缩放、透明度。 */
    private void syncGeometry() {
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0) return;

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && (lp.width != w || lp.height != h)) {
            lp.width = w;
            lp.height = h;
            setLayoutParams(lp);
        }

        int left = host.getLeft();
        int top = host.getTop();
        if (getLeft() != left || getTop() != top
                || getRight() != left + w || getBottom() != top + h) {
            layout(left, top, left + w, top + h);
        }

        if (getTranslationX() != host.getTranslationX()) {
            setTranslationX(host.getTranslationX());
        }
        if (getTranslationY() != host.getTranslationY()) {
            setTranslationY(host.getTranslationY());
        }
        if (getScaleX() != host.getScaleX() || getScaleY() != host.getScaleY()) {
            setScaleX(host.getScaleX());
            setScaleY(host.getScaleY());
            setPivotX(host.getPivotX());
            setPivotY(host.getPivotY());
        }
        // 墨墨把透明度加在球自己那个子 View 上（容器 alpha 一直是 1），
        // 只跟容器的话覆盖层会把球画成不透明，看起来就是「操作几下透明度没了」。
        float target = host.getAlpha();
        View ball = BallOverlay.ballChild(host);
        if (ball != null) target *= ball.getAlpha();
        if (Math.abs(getAlpha() - target) > 0.004f) {
            setAlpha(target);
        }
        if (Math.abs(target - lastLoggedAlpha) > 0.05f) {
            lastLoggedAlpha = target;
            XposedLog.d("覆盖层透明度 host=" + host.getAlpha()
                    + " 球=" + (ball == null ? 1f : ball.getAlpha()) + " → " + target);
        }
        if (getVisibility() != host.getVisibility()) {
            setVisibility(host.getVisibility());
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // ViewTreeObserver 会在控件重新 attach 时换新，旧的监听器跟着失效，
        // 覆盖层就再也不会跟着球走（表现为停在 (0,0) 不动）。这里重新挂一次。
        registerPreDraw();
        syncGeometry();
    }

    private void registerPreDraw() {
        try {
            ViewTreeObserver vto = getViewTreeObserver();
            vto.removeOnPreDrawListener(preDraw);
            vto.addOnPreDrawListener(preDraw);
        } catch (Throwable ignored) {
        }
    }

    void detach() {
        try {
            host.getViewTreeObserver().removeOnPreDrawListener(preDraw);
        } catch (Throwable ignored) {
        }
        try {
            ViewParent p = getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        paint.setBounds(0, 0, getWidth(), getHeight());
        paint.draw(canvas);
    }
}
