package com.fuoins.momoreviewball;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * 球面绘制器：把「底色 + 四向色带 + 中心动作圆点」画在一颗指定圆心/半径的圆上。
 *
 * <p>复习页的 {@link BallOverlay} 和设置页的大小预览共用这一份画法，
 * 保证预览里看到的颜色、比例、文字和真球完全一致。
 */
final class BallPainter {

    private static final String[] SHORT_LABELS = {
            "无", "认识", "模糊", "忘记", "熟知", "发音", "显示", "瞄", "返回"
    };

    /** 上 / 下 / 左 / 右 四条色带的中心角（0° 在 3 点钟方向，顺时针）。 */
    private static final float[] BAND_ANGLE = {270f, 90f, 180f, 0f};

    /** 球面底色：盖住原生内容，同时保持球是浅色的，和墨墨整体风格一致。 */
    static final int BASE_COLOR = 0xFFF7F8FA;
    static final int BADGE_COLOR = 0xF21F2430;
    static final int EDGE_COLOR = 0x1A000000;
    static final int IDLE_COLOR = 0xFFDDE1E6;
    static final int NONE_COLOR = 0xFFB9C0CC;

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF band = new RectF();

    BallPainter(Context context) {
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
    }

    /**
     * @param highlight 0 表示静止，1~4 对应上/下/左/右正在滑动
     * @param progress  滑动幅度 0~1，用于让当前方向的色带变粗一点
     */
    void draw(Canvas canvas, float cx, float cy, float radius, Config config,
              boolean after, int highlight, float progress) {
        if (radius <= 0f) return;

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

            // 没配动作时色带保持浅灰弱化；但用户若专门给「无操作」设了颜色，也照样生效
            arcPaint.setColor(idle && !config.hasCustomColor(action)
                    ? IDLE_COLOR : config.colorOf(action));
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
        badgePaint.setColor(config.colorOf(action));
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

    private String shortLabel(String action) {
        for (int i = 0; i < Config.ACTION_KEYS.length; i++) {
            if (Config.ACTION_KEYS[i].equals(action)) return SHORT_LABELS[i];
        }
        return "";
    }
}
