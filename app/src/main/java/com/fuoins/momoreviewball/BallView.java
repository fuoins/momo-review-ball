package com.fuoins.momoreviewball;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;

/** 悬浮球本体：深蓝圆球 + 四向箭头，锁定后右下角出现小锁。 */
public class BallView extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();

    private int alphaPercent = 88;
    private boolean locked;
    private float flash;
    private boolean flashOk;

    public BallView(Context context) {
        this(context, null);
    }

    public BallView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
        setElevation(dp(8));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        glyphPaint.setColor(0xF2FFFFFF);
        glyphPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.1f));
        ringPaint.setColor(0x44FFFFFF);
        accentPaint.setColor(0xFFF2A33C);
        accentPaint.setStyle(Paint.Style.FILL);
    }

    public void applyConfig(Config config) {
        this.alphaPercent = config.alpha;
        this.locked = config.locked;
        invalidate();
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        invalidate();
    }

    public void flash(boolean ok) {
        this.flashOk = ok;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(520);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            flash = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f - dp(5.0f);

        int alpha = Math.round(255f * alphaPercent / 100f);
        fillPaint.setColor(Color.argb(alpha, 47, 62, 110));
        canvas.drawCircle(cx, cy, radius, fillPaint);
        canvas.drawCircle(cx, cy, radius, ringPaint);

        for (int i = 0; i < 4; i++) {
            canvas.save();
            canvas.rotate(i * 90f, cx, cy);
            chevron.reset();
            float tipY = cy - radius * 0.52f - dp(4.2f);
            float baseY = cy - radius * 0.52f + dp(2.6f);
            chevron.moveTo(cx, tipY);
            chevron.lineTo(cx - dp(4.0f), baseY);
            chevron.lineTo(cx + dp(4.0f), baseY);
            chevron.close();
            canvas.drawPath(chevron, glyphPaint);
            canvas.restore();
        }

        accentPaint.setAlpha(255);
        canvas.drawCircle(cx, cy, dp(3.0f), accentPaint);

        if (locked) {
            drawLock(canvas, cx + radius * 0.60f, cy + radius * 0.60f);
        }

        if (flash > 0f) {
            ringPaint.setStrokeWidth(dp(1.6f));
            ringPaint.setColor(flashOk ? 0xFF54C08A : 0xFFE05A5A);
            ringPaint.setAlpha((int) (255 * (1f - flash)));
            canvas.drawCircle(cx, cy, radius + dp(1.5f) + dp(3f) * flash, ringPaint);
            ringPaint.setStrokeWidth(dp(1.1f));
            ringPaint.setColor(0x44FFFFFF);
        }
    }

    private void drawLock(Canvas canvas, float x, float y) {
        float badge = dp(9.0f);
        accentPaint.setColor(0xFFF2A33C);
        canvas.drawCircle(x, y, badge, accentPaint);

        lockPaint.setColor(0xFF2F3E6E);
        lockPaint.setStyle(Paint.Style.FILL);
        RectF body = new RectF(x - dp(3.6f), y - dp(0.8f), x + dp(3.6f), y + dp(4.2f));
        canvas.drawRoundRect(body, dp(1.2f), dp(1.2f), lockPaint);

        lockPaint.setStyle(Paint.Style.STROKE);
        lockPaint.setStrokeWidth(dp(1.3f));
        RectF shackle = new RectF(x - dp(2.6f), y - dp(5.0f), x + dp(2.6f), y - dp(0.2f));
        canvas.drawArc(shackle, 180f, 180f, false, lockPaint);
        lockPaint.setStyle(Paint.Style.FILL);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
