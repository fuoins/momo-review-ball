package com.fuoins.momoreviewball;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


/**
 * 盖在墨墨自带悬浮球上的一层「球面」。
 *
 * <p>绘制交给 {@link BallPainter}，这里只负责：
 * <ul>
 *   <li>把 bounds 对齐到控件，并按控件里那颗真球（{@code xj6} 224×224）算出圆心半径；</li>
 *   <li>跟踪「答案显示前 / 后」，切换用哪一套配色和文字；</li>
 *   <li>滑动时把当前方向的色带加粗。</li>
 * </ul>
 */
final class BallOverlay extends Drawable {

    /** 评分栏还没出现时多久复查一次；配合显隐监听，正常切换几乎是即时的。 */
    private static final long POLL_MS = 200L;

    private static final List<WeakReference<BallOverlay>> LIVE = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sRefreshPending;

    private final View host;
    private final BallPainter painter;
    private final RectF ball = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean after;
    private boolean running;
    private boolean reported;
    private boolean reportedZero;
    private int highlight = 0;
    private float progress = 0f;
    private int seenRevision = -1;
    private int barId;
    private WeakReference<View> barRef;

    private final Runnable clearHighlight = () -> {
        highlight = 0;
        progress = 0f;
        refresh();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            syncAnswerState();
            handler.postDelayed(this, POLL_MS);
        }
    };

    BallOverlay(View host) {
        this.host = host;
        this.painter = new BallPainter(host.getContext());
        synchronized (LIVE) {
            LIVE.add(new WeakReference<>(this));
        }
        XposedLog.i("BallOverlay 已创建，host=" + host.getClass().getSimpleName());
    }

    void start() {
        if (running) return;
        running = true;
        after = detectAfter();
        seenRevision = Config.revision();
        handler.postDelayed(tick, POLL_MS);
    }

    /**
     * 任意控件的显隐变化都可能意味着评分栏出现/消失。
     *
     * <p>合并成一次延迟刷新，避免一次布局里几十个 setVisibility 把主线程刷爆。
     */
    static void requestRefresh() {
        if (sRefreshPending) return;
        sRefreshPending = true;
        MAIN.postDelayed(() -> {
            sRefreshPending = false;
            List<BallOverlay> alive = new ArrayList<>();
            synchronized (LIVE) {
                for (Iterator<WeakReference<BallOverlay>> it = LIVE.iterator(); it.hasNext(); ) {
                    BallOverlay overlay = it.next().get();
                    if (overlay == null) {
                        it.remove();
                    } else {
                        alive.add(overlay);
                    }
                }
            }
            for (BallOverlay overlay : alive) {
                overlay.syncAnswerState();
            }
        }, 60L);
    }

    static boolean hasLive() {
        synchronized (LIVE) {
            return !LIVE.isEmpty();
        }
    }

    /** 执行完一个手势后连续复查几次，评分栏动画一结束就能切到「出现后」。 */
    void kick() {
        syncAnswerState();
        handler.postDelayed(this::syncAnswerState, 90L);
        handler.postDelayed(this::syncAnswerState, 240L);
        handler.postDelayed(this::syncAnswerState, 560L);
    }

    private void syncAnswerState() {
        if (!running) return;
        int revision = Config.revision();
        boolean changed = revision != seenRevision;
        seenRevision = revision;
        boolean now = detectAfter();
        if (now != after || changed) {
            after = now;
            refresh();
        }
    }

    /**
     * 重绘请求。
     *
     * <p>{@code invalidateSelf()} 靠 callback 冒泡到控件；foreground 模式下 callback
     * 指向控件，但换过承载方式时不一定可靠，顺手再叫一次控件重绘。
     */
    private void refresh() {
        invalidateSelf();
        try {
            host.invalidate();
        } catch (Throwable ignored) {
        }
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(clearHighlight);
    }

    /** 手指滑动中：gesture 0 表示没有手势，1~4 对应上/下/左/右。 */
    void setHighlight(int gesture, float ratio) {
        this.highlight = gesture;
        this.progress = Math.max(0f, Math.min(1f, ratio));
        handler.removeCallbacks(clearHighlight);
        refresh();
        if (gesture == 0) {
            handler.postDelayed(clearHighlight, 140L);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        // overlay 模式下 bounds 常常和控件对不上（实测 582 vs 控件 448），
        // 所以每次绘制都按控件实际尺寸强制对齐。
        int hostW = host.getWidth();
        int hostH = host.getHeight();
        Rect b = getBounds();
        if (hostW > 0 && hostH > 0 && (b.width() != hostW || b.height() != hostH)) {
            setBounds(0, 0, hostW, hostH);
            b = getBounds();
        }
        if (b.width() <= 0 || b.height() <= 0) {
            if (!reportedZero) {
                reportedZero = true;
                XposedLog.w("球面标识拿不到尺寸：bounds=0，控件也还是 0×0");
            }
            return;
        }

        Config config = Config.get(host.getContext());
        if (!config.enabled) return;

        Context context = host.getContext();

        // 球体区域：控件本身比球大一圈（448 的容器里那颗球只有 224），
        // 按控件画一定偏，必须以球自己那个子 View 为准。
        if (!childBallRect(ball)) {
            float side = Math.min(b.width(), b.height());
            float left = b.exactCenterX() - side / 2f;
            float top = b.exactCenterY() - side / 2f;
            ball.set(left, top, left + side, top + side);
        }
        float radius = Math.min(ball.width(), ball.height()) / 2f;
        if (radius < Util.dp(context, 8f)) return;

        // 球体自己可能带阴影，往外留一点点，别把球撑大。
        // 只留 1%：原生那条色环就贴在球的最外沿，留多了会露出一条原来的颜色。
        ball.inset(radius * 0.01f, radius * 0.01f);
        radius = Math.min(ball.width(), ball.height()) / 2f;
        float cx = ball.centerX();
        float cy = ball.centerY();

        if (!reported) {
            reported = true;
            Rect clip = new Rect();
            canvas.getClipBounds(clip);
            XposedLog.i("球面标识开始绘制 控件=" + b.width() + "x" + b.height()
                    + " 球=" + Math.round(radius * 2) + " 圆心=(" + Math.round(cx)
                    + "," + Math.round(cy) + ") 裁剪区=" + clip.width() + "x" + clip.height());
        }

        painter.draw(canvas, cx, cy, radius, config, after, highlight, progress);
    }

    /**
     * 在控件内部找真正的那颗球：可见、宽高接近、面积最大的子 View。
     *
     * <p>墨墨把球画在一个比球本身大一倍的容器里（多出来的部分是阴影和拖拽热区），
     * 直接按容器画圆一定会偏，所以有子 View 就以子 View 为准。
     *
     * @return true 表示找到了并把坐标写进 out（坐标系与 Drawable bounds 一致）
     */
    private boolean childBallRect(RectF out) {
        if (!(host instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) host;
        if (group.getChildCount() == 0) return false;

        View best = null;
        long bestArea = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            int w = child.getWidth();
            int h = child.getHeight();
            if (w <= 0 || h <= 0) continue;
            // 只要接近正方形的：球是圆的，长条形的多半是阴影层或文字
            if (Math.abs(w - h) > Math.min(w, h) * 0.15f) continue;
            long area = (long) w * h;
            if (area > bestArea) {
                bestArea = area;
                best = child;
            }
        }
        if (best == null) return false;

        out.set(best.getLeft(), best.getTop(), best.getRight(), best.getBottom());
        return true;
    }

    /** 当前是否已经显示答案：评分栏可见即视为已显示。 */
    private boolean detectAfter() {
        try {
            Context context = host.getContext();
            if (barId == 0) {
                barId = context.getResources()
                        .getIdentifier("rev_cl_operation_bar", "id", context.getPackageName());
            }
            if (barId == 0) return after;
            View bar = barRef == null ? null : barRef.get();
            if (bar == null || !bar.isAttachedToWindow()) {
                // 只在第一次或页面重建后查一次树，之后每次只是一个 isShown()
                bar = host.getRootView().findViewById(barId);
                barRef = new WeakReference<>(bar);
                if (bar == null) return after;
            }
            return bar.isShown();
        } catch (Throwable t) {
            return after;
        }
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
