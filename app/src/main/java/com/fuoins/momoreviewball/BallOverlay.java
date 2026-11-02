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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


/**
 * 盖在墨墨自带悬浮球上的一层「球面」。
 *
 * <p>绘制顺序刻意做成「先铺底、再画内容」：
 * <ol>
 *   <li>先用不透明底色画满整颗球，把原生球面上的方向图标、方向色环整个盖掉——
 *       之前只画四条细弧，原生的东西从弧线内外两头露出来，看起来就是「没盖住」；</li>
 *   <li>再沿球内壁画四段粗色带，颜色跟着「上/下/左/右」当前映射的动作走；</li>
 *   <li>最后在正中间写「单击」当前对应的动作，滑动时换成该方向将执行的动作。</li>
 * </ol>
 *
 * <p>几何一律以「控件里那颗真正的球」为准（{@code xj6} 224×224，居中），
 * 而不是控件的整个矩形——控件比球大一倍，按控件画会整整偏出一圈。
 */
final class BallOverlay extends Drawable {

    private static final String[] SHORT_LABELS = {
            "无", "认识", "模糊", "忘记", "熟知", "发音", "显示", "瞄一眼", "返回"
    };

    /** 上 / 下 / 左 / 右 四条色带的中心角（0° 在 3 点钟方向，顺时针）。 */
    private static final float[] BAND_ANGLE = {270f, 90f, 180f, 0f};

    /** 球面底色：盖住原生内容，同时保持球是浅色的，和墨墨整体风格一致。 */
    private static final int BASE_COLOR = 0xFFF7F8FA;
    private static final int BADGE_COLOR = 0xF21F2430;
    private static final int EDGE_COLOR = 0x1A000000;
    private static final int IDLE_COLOR = 0xFFDDE1E6;
    private static final int NONE_COLOR = 0xFFB9C0CC;

    /** 评分栏还没出现时多久复查一次；配合显隐监听，正常切换几乎是即时的。 */
    private static final long POLL_MS = 200L;

    private static final List<WeakReference<BallOverlay>> LIVE = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sRefreshPending;

    private final View host;
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ball = new RectF();
    private final RectF band = new RectF();
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
        Context context = host.getContext();
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.BUTT);
        basePaint.setStyle(Paint.Style.FILL);
        basePaint.setColor(BASE_COLOR);
        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setColor(BADGE_COLOR);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(1f, Util.dp(context, 1.6f)));
        ringPaint.setColor(0xFFFFFFFF);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(Math.max(1f, Util.dp(context, 0.7f)));
        edgePaint.setColor(EDGE_COLOR);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
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

        // ① 不透明底盘：把原生的方向图标、方向色环整个盖掉
        basePaint.setColor(BASE_COLOR);
        basePaint.setAlpha(255);
        canvas.drawCircle(cx, cy, radius, basePaint);
        canvas.drawCircle(cx, cy, radius - edgePaint.getStrokeWidth() / 2f, edgePaint);

        // ② 四段粗色带：贴着球内壁，位置正好压住原生那圈方向色。
        //    墨墨原生的四色是「连成一整圈」的，所以这里也几乎连成整圈（每段 86°，缝 4°），
        //    否则留白处会露出原生那一圈，看着就像没盖干净。
        float bandWidth = radius * 0.28f;
        float bandRadius = radius - bandWidth / 2f - radius * 0.01f;
        band.set(cx - bandRadius, cy - bandRadius, cx + bandRadius, cy + bandRadius);

        for (int i = 0; i < 4; i++) {
            int gesture = i + 1;
            String action = config.actionFor(after, gesture);
            boolean idle = Config.ACTION_NONE.equals(action);
            boolean active = highlight == gesture && progress > 0f;
            boolean dim = highlight != 0 && !active;

            arcPaint.setColor(idle ? IDLE_COLOR : SettingsPanel.colorOf(action));
            arcPaint.setAlpha(dim ? 96 : 255);
            arcPaint.setStrokeWidth(active ? bandWidth * (1f + 0.14f * progress) : bandWidth);

            float sweep = active ? 82f + 14f * progress : 86f;
            canvas.drawArc(band, BAND_ANGLE[i] - sweep / 2f, sweep, false, arcPaint);
        }

        // ③ 中心：静止时是「单击」的动作，滑动中换成该方向的动作。
        //    底色直接取这个动作在设置页里的配色——认识就是绿、忘记就是红，
        //    一眼就能看出现在点下去会发生什么；颜色跟着「出现前 / 出现后」一起变。
        int shown = highlight > 0 ? highlight : 0;
        String action = config.actionFor(after, shown);
        String label = shortLabel(action);
        if (label.isEmpty()) return;

        float badgeRadius = radius * (highlight > 0 ? 0.44f : 0.40f);
        badgePaint.setColor(Config.ACTION_NONE.equals(action)
                ? NONE_COLOR : SettingsPanel.colorOf(action));
        canvas.drawCircle(cx, cy, badgeRadius, badgePaint);

        // 一圈白边，把中心和四周的色带分开，颜色再接近也看得清
        canvas.drawCircle(cx, cy, badgeRadius - ringPaint.getStrokeWidth() / 2f, ringPaint);

        float textSize = label.length() >= 3 ? badgeRadius * 0.56f : badgeRadius * 0.72f;
        textPaint.setTextSize(textSize);
        textPaint.setColor(Color.WHITE);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, cx, baseline, textPaint);
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
