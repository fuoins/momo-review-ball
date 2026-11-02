package com.fuoins.momoreviewball;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/**
 * 设置页里的悬浮球预览。
 *
 * <p>用和真球完全相同的 {@link BallPainter} 画，尺寸也取真球的实测 dp 直径，
 * 所以滑杆 1.0 时预览的物理大小就是复习页那颗球的大小，拖到 1.5 也是同样的比例，
 * 不是「随手画一个大概」的示意图。
 */
final class BallPreview extends View {

    /** 1.0 倍时真球的半径（dp），由 BallOverlay 实测后写入；没测过就用 74.7dp 直径兜底。 */
    private final float baseRadiusDp;

    private final BallPainter painter;
    private final Config config;
    private float scale = 1f;

    BallPreview(Context context, Config config) {
        super(context);
        this.config = config;
        this.painter = new BallPainter(context);
        this.baseRadiusDp = Config.nativeBallDiameterDp(context) / 2f;

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF4F6FA);
        bg.setCornerRadius(Util.dp(context, 14));
        setBackground(bg);
    }

    void setScale(float value) {
        float next = Config.clamp(value, Config.SCALE_MIN, Config.SCALE_MAX);
        if (Math.abs(next - scale) < 0.001f) return;
        scale = next;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = Util.dp(getContext(), baseRadiusDp * 2f * Config.SCALE_MAX + 32f);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = Util.dp(getContext(), 300);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Util.dp(getContext(), baseRadiusDp) * scale;
        float limit = Math.min(getWidth(), getHeight()) / 2f - Util.dp(getContext(), 10f);
        if (radius > limit) radius = limit;
        painter.draw(canvas, cx, cy, radius, config, false, 0, 0f);
    }
}
