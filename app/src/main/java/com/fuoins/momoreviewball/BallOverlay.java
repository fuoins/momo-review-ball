package com.fuoins.momoreviewball;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;


/**
 * 盖在墨墨自带悬浮球上的一层标识。
 *
 * <ul>
 *   <li>中心小字：显示「单击」当前对应的动作，随「单词出现前 / 出现后」切换；</li>
 *   <li>滑动时：对应方向的弧线加粗高亮、中心文字立刻变成该方向将执行的动作，
 *       抬手后淡出——这是模块自己的手势动画，不依赖原生、也不会触发原生动作；</li>
 *   <li>边缘四向色带：沿球体轮廓画四段彩色弧，颜色跟着当前映射动作走。</li>
 * </ul>
 */
final class BallOverlay extends Drawable {

    private static final String[] SHORT_LABELS = {
            "无", "认识", "模糊", "忘记", "熟知", "发音", "显示", "瞄一眼", "返回"
    };

    /** 上 / 下 / 左 / 右 四段弧的起始角（0° 在 3 点钟方向，顺时针）。 */
    private static final int[] ARC_START = {242, 62, 152, -28};

    private View host;
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean after;
    private boolean running;
    private boolean reported;
    private boolean reportedZero;
    private int highlight = 0;
    private float progress = 0f;

    private final Runnable clearHighlight = () -> {
        highlight = 0;
        progress = 0f;
        refresh();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            boolean now = detectAfter();
            if (now != after) {
                after = now;
                refresh();
            }
            handler.postDelayed(this, 800L);
        }
    };

    BallOverlay(View host) {
        this.host = host;
        Context context = host.getContext();
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStrokeWidth(Util.dp(context, 2.4f));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        badgePaint.setColor(0xC4141B2E);
        badgePaint.setStyle(Paint.Style.FILL);
        XposedLog.i("BallOverlay 已创建，host=" + host.getClass().getSimpleName());
    }

    /** 绘制挂点换到真正自绘的那个子 View 上时，宽高也要跟着换。 */
    void setPaintHost(View target) {
        if (target != null) this.host = target;
    }

    void start() {
        if (running) return;
        running = true;
        after = detectAfter();
        handler.postDelayed(tick, 800L);
    }

    /**
     * 重绘请求。
     *
     * <p>直接用 drawable 时 {@code invalidateSelf()} 靠 callback 冒泡到控件，
     * 但这里没有走 {@code getOverlay().add()}，callback 是空的，必须自己叫醒控件。
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
        // 画出来的弧整个落到球外面，所以每次绘制都按控件实际尺寸强制对齐。
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

        if (!reported) {
            reported = true;
            Rect clip = new Rect();
            canvas.getClipBounds(clip);
            XposedLog.i("球面标识开始绘制 " + b.width() + "x" + b.height()
                    + "，子View=" + (host instanceof ViewGroup
                            ? ((ViewGroup) host).getChildCount() : -1)
                    + "，裁剪区=" + clip.width() + "x" + clip.height());
        }

        Context context = host.getContext();

        // 墨墨的球控件本身不是正方形（5.6 上是 493×582），整个矩形当球画会变成椭圆、
        // 上下两端飘到球外。优先取控件内部那个接近正方形、面积最大的子 View 当球体；
        // 取不到就退回「以控件中心为圆心、边长取短边」的正方形。
        float inset = Util.dp(context, 4.0f);
        oval.set(b.left, b.top, b.right, b.bottom);
        if (!childBallRect(oval)) {
            float side = Math.min(b.width(), b.height());
            float left = b.exactCenterX() - side / 2f;
            float top = b.exactCenterY() - side / 2f;
            oval.set(left, top, left + side, top + side);
        }
        oval.inset(inset, inset);

        float cx = oval.centerX();
        float cy = oval.centerY();
        float radius = Math.min(oval.width(), oval.height()) / 2f;

        for (int i = 0; i < 4; i++) {
            int gesture = i + 1;
            String action = config.actionFor(after, gesture);
            if (Config.ACTION_NONE.equals(action)) continue;

            boolean active = highlight == gesture && progress > 0f;
            boolean dim = highlight != 0 && !active;

            arcPaint.setColor(SettingsPanel.colorOf(action));
            arcPaint.setAlpha(dim ? 70 : (active ? 255 : 235));
            arcPaint.setStrokeWidth(Util.dp(context, active ? 7.0f : 5.0f));

            float sweep = active ? 46f + 30f * progress : 58f;
            canvas.drawArc(oval, ARC_START[i], sweep, false, arcPaint);
        }

        // 滑动中显示该方向的动作，静止时显示单击的动作
        int shown = highlight > 0 ? highlight : 0;
        String action = config.actionFor(after, shown);
        if (Config.ACTION_NONE.equals(action)) return;

        float badgeRadius = radius * (highlight > 0 ? 0.40f : 0.34f);
        badgePaint.setColor(highlight > 0 ? 0xE6102340 : 0xC4141B2E);
        canvas.drawCircle(cx, cy, badgeRadius, badgePaint);

        String label = shortLabel(action);
        float textSize = label.length() >= 3 ? badgeRadius * 0.62f : badgeRadius * 0.82f;
        textPaint.setTextSize(textSize);
        textPaint.setColor(Color.WHITE);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, cx, baseline, textPaint);
    }

    /**
     * 在控件内部找真正的那颗球：可见、宽高接近、面积最大的子 View。
     *
     * <p>墨墨把球画在一个比球本身高出一截的容器里（多出来的部分通常是阴影或拖拽热区），
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

    private String shortLabel(String action) {
        for (int i = 0; i < Config.ACTION_KEYS.length; i++) {
            if (Config.ACTION_KEYS[i].equals(action)) return SHORT_LABELS[i];
        }
        return "";
    }

    /** 评分栏可见即视为答案已显示，用于切换取自「出现前」还是「出现后」。 */
    private boolean detectAfter() {
        try {
            Context context = host.getContext();
            int id = context.getResources()
                    .getIdentifier("rev_cl_operation_bar", "id", context.getPackageName());
            if (id == 0) return after;
            View bar = host.getRootView().findViewById(id);
            return bar != null && bar.isShown();
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
