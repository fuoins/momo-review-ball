package com.fuoins.momoreviewball;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * 盖在墨墨自带悬浮球上的一层标识。
 *
 * <ul>
 *   <li>中心小字：显示「单击」当前对应的动作，随「单词出现前 / 出现后」切换；</li>
 *   <li>滑动时：对应方向的弧线加粗高亮、中心文字立刻变成该方向将执行的动作，
 *       抬手后淡出——这是模块自己的手势动画，不依赖原生、也不会触发原生动作；</li>
 *   <li>兜底弧线：只有在球体内部没有可染色的图标时才画四向弧。</li>
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
    private boolean drawArcs = true;

    private int highlight = 0;
    private float progress = 0f;

    /** 球体内部的四个方向图标，直接染色比在上面盖弧线更贴合原生。 */
    private final List<ImageView> icons = new ArrayList<>();
    private final List<Integer> iconGestures = new ArrayList<>();
    private final List<Integer> iconColors = new ArrayList<>();

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
            tintIcons(Config.get(host.getContext()));
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

    void setDrawArcs(boolean drawArcs) {
        this.drawArcs = drawArcs;
        invalidateSelf();
    }

    void start() {
        if (running) return;
        running = true;
        after = detectAfter();
        bindIcons();
        handler.postDelayed(tick, 800L);
    }

    /** 扫描球里的方向图标并按位置分组；没有图标时退化为画弧。 */
    void bindIcons() {
        icons.clear();
        iconGestures.clear();
        iconColors.clear();
        int cx = host.getWidth() / 2;
        int cy = host.getHeight() / 2;
        if (cx > 0 && cy > 0) {
            collectIcons(host, cx, cy);
        }
        setDrawArcs(icons.isEmpty());
        tintIcons(Config.get(host.getContext()));
    }

    private void collectIcons(View view, int cx, int cy) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView) {
                float x = child.getLeft() + child.getWidth() / 2f;
                float y = child.getTop() + child.getHeight() / 2f;
                icons.add((ImageView) child);
                iconGestures.add(gestureOf(x - cx, y - cy, host.getWidth()));
                iconColors.add(0);
            } else {
                collectIcons(child, cx, cy);
            }
        }
    }

    private static int gestureOf(float dx, float dy, int size) {
        if ((float) Math.hypot(dx, dy) < size * 0.18f) return 0;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? 4 : 3;
        return dy > 0 ? 2 : 1;
    }

    private void tintIcons(Config config) {
        for (int i = 0; i < icons.size(); i++) {
            String action = config.actionFor(after, iconGestures.get(i));
            int color = Config.ACTION_NONE.equals(action) ? 0 : SettingsPanel.colorOf(action);
            if (color == iconColors.get(i)) continue;
            iconColors.set(i, color);
            try {
                ImageView icon = icons.get(i);
                if (color == 0) {
                    icon.clearColorFilter();
                } else {
                    icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                }
            } catch (Throwable ignored) {
            }
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
        float radius = Math.min(b.width(), b.height()) / 2f;
        float arcRadius = radius - Util.dp(context, 3.0f);

        for (int i = 0; i < 4; i++) {
            int gesture = i + 1;
            String action = config.actionFor(after, gesture);
            if (Config.ACTION_NONE.equals(action)) continue;

            boolean active = highlight == gesture && progress > 0f;
            boolean dim = highlight != 0 && !active;

            arcPaint.setColor(SettingsPanel.colorOf(action));
            arcPaint.setAlpha(dim ? 60 : (active ? 255 : 150));
            arcPaint.setStrokeWidth(Util.dp(context, active ? 3.6f : 2.4f));

            if (drawArcs || active) {
                oval.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius);
                float sweep = active ? 40f + 32f * progress : 54f;
                canvas.drawArc(oval, ARC_START[i], sweep, false, arcPaint);
            }
        }

        // 滑动中显示该方向的动作，静止时显示单击的动作
        int shown = highlight > 0 ? highlight : 0;
        String action = config.actionFor(after, shown);
        if (Config.ACTION_NONE.equals(action)) return;

        float badgeRadius = radius * (highlight > 0 ? 0.50f : 0.44f);
        badgePaint.setColor(highlight > 0 ? 0xE6102340 : 0xC4141B2E);
        canvas.drawCircle(cx, cy, badgeRadius, badgePaint);

        String label = shortLabel(action);
        float textSize = label.length() >= 3 ? radius * 0.29f : radius * 0.38f;
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
