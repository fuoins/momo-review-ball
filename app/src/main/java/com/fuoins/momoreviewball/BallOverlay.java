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

    private final View host;
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean after;
    private boolean running;
    private int highlight = 0;
    private float progress = 0f;

    private final Runnable clearHighlight = () -> {
        highlight = 0;
        progress = 0f;
        invalidateSelf();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            boolean now = detectAfter();
            if (now != after) {
                after = now;
                invalidateSelf();
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
    }

    void start() {
        if (running) return;
        running = true;
        after = detectAfter();
        handler.postDelayed(tick, 800L);
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
        invalidateSelf();
        if (gesture == 0) {
            handler.postDelayed(clearHighlight, 140L);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;

        Config config = Config.get(host.getContext());
        if (!config.enabled) return;

        Context context = host.getContext();
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float side = Math.min(b.width(), b.height());
        float radius = side / 2f;

        // 沿控件真实轮廓内缩一圈画色带；球体不一定是正圆，用椭圆更贴合
        float inset = Util.dp(context, 4.0f);
        oval.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);

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
