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
 * 盖在墨墨自带悬浮球上的一层标识：边缘四个方向按当前映射的动作上色，
 * 中心用小字显示「单击」当前对应的动作。
 *
 * <p>用 {@link View#getOverlay()} 叠加，不修改墨墨控件本身，也不拦截任何触摸。
 */
final class BallOverlay extends Drawable {

    private static final String[] SHORT_LABELS = {
            "无", "认识", "模糊", "忘记", "熟知", "发音", "显示", "瞄一眼", "返回"
    };

    private static final int[] ARC_START = {242, 62, 152, -28};

    private final View host;
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean after;
    private boolean running;

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
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStrokeWidth(Util.dp(host.getContext(), 2.6f));
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
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;

        Config config = Config.get(host.getContext());
        if (!config.enabled) return;

        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float radius = Math.min(b.width(), b.height()) / 2f;
        float arcRadius = radius - Util.dp(host.getContext(), 3.2f);

        // 四向弧：上滑 / 下滑 / 左滑 / 右滑
        for (int i = 0; i < 4; i++) {
            int gesture = i + 1;
            String action = config.actionFor(after, gesture);
            if (Config.ACTION_NONE.equals(action)) continue;
            arcPaint.setColor(SettingsPanel.colorOf(action));
            oval.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius);
            canvas.drawArc(oval, ARC_START[i], 54f, false, arcPaint);
        }

        // 中心：单击当前对应的动作
        String action = config.actionFor(after, 0);
        if (Config.ACTION_NONE.equals(action)) return;

        float badgeRadius = radius * 0.44f;
        canvas.drawCircle(cx, cy, badgeRadius, badgePaint);

        String label = shortLabel(action);
        float textSize = label.length() >= 3 ? radius * 0.30f : radius * 0.38f;
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

    /** 评分栏可见即视为答案已显示，用于切换颜色取自「出现前」还是「出现后」。 */
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
