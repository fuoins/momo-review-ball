package com.fuoins.momoreviewball;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/**
 * 设置页里的悬浮球预览。
 *
 * <p>用和真球完全相同的 {@link BallPainter} 画，尺寸跟着滑杆走，
 * 所以拖滑杆时看到的大小比例就是复习页上的大小比例。
 */
final class BallPreview extends View {

    /** 1.0 倍时预览球的半径（dp）。只是为了看得清，和真球的物理尺寸无关。 */
    private static final float BASE_RADIUS_DP = 50f;

    private final BallPainter painter;
    private final Config config;
    private float scale = 1f;

    BallPreview(Context context, Config config) {
        super(context);
        this.config = config;
        this.painter = new BallPainter(context);

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
        int height = Util.dp(getContext(), BASE_RADIUS_DP * 2f * Config.SCALE_MAX + 32f);
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
        float radius = Util.dp(getContext(), BASE_RADIUS_DP) * scale;
        float limit = Math.min(getWidth(), getHeight()) / 2f - Util.dp(getContext(), 10f);
        if (radius > limit) radius = limit;
        painter.draw(canvas, cx, cy, radius, config, false, 0, 0f);
    }
}
