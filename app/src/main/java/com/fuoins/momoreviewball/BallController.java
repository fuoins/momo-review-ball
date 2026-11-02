package com.fuoins.momoreviewball;

import android.app.Activity;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 把悬浮球挂到复习页的 content 视图上，处理拖动与手势。
 */
public final class BallController implements View.OnTouchListener {

    private static final String TAG = "MoMoBall";
    private static final Map<Activity, BallController> ATTACHED = new WeakHashMap<>();

    private final Activity activity;
    private final FrameLayout root;
    private final BallView ball;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;
    private final View.OnLayoutChangeListener layoutListener;
    private final ContentObserver observer;

    private FrameLayout.LayoutParams lp;
    private Config config;

    private float downRawX;
    private float downRawY;
    private int baseLeft;
    private int baseTop;
    private boolean dragging;
    private boolean longFired;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (dragging) return;
            longFired = true;
            boolean target = !config.locked;
            Config.Client.setLocked(activity, target);
            config.locked = target;
            ball.setLocked(target);
            ball.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            ActionExecutor.toast(activity, target ? "已固定位置，长按可解锁" : "已解锁，可以拖动");
        }
    };

    private BallController(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        this.config = Config.Client.get(activity);
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

        this.ball = new BallView(activity);
        this.ball.setOnTouchListener(this);
        this.ball.setContentDescription("复习手势悬浮球");

        int size = dp(config.sizeDp);
        this.lp = new FrameLayout.LayoutParams(size, size);
        this.lp.gravity = Gravity.TOP | Gravity.START;
        root.addView(ball, lp);

        this.layoutListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!dragging) applyConfig();
        };
        root.addOnLayoutChangeListener(layoutListener);

        this.observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                Config.Client.invalidate();
                applyConfig();
            }
        };
        try {
            activity.getContentResolver().registerContentObserver(Config.URI, false, observer);
        } catch (Throwable t) {
            Log.w(TAG, "register observer failed", t);
        }
    }

    public static void attach(Activity activity) {
        if (activity == null) return;
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(activity)) return;
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) {
                Log.w(TAG, "content view is not FrameLayout: " + content);
                return;
            }
            try {
                BallController controller = new BallController(activity, (FrameLayout) content);
                ATTACHED.put(activity, controller);
                controller.ball.post(controller::applyConfig);
            } catch (Throwable t) {
                Log.e(TAG, "attach failed", t);
            }
        }
    }

    public static void detach(Activity activity) {
        if (activity == null) return;
        BallController controller;
        synchronized (ATTACHED) {
            controller = ATTACHED.remove(activity);
        }
        if (controller != null) controller.release();
    }

    private void release() {
        handler.removeCallbacksAndMessages(null);
        try {
            activity.getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) {
        }
        try {
            root.removeOnLayoutChangeListener(layoutListener);
        } catch (Throwable ignored) {
        }
        try {
            root.removeView(ball);
        } catch (Throwable ignored) {
        }
    }

    private void applyConfig() {
        if (dragging) return;
        config = Config.Client.get(activity);
        ball.applyConfig(config);
        ball.setVisibility(config.enabled ? View.VISIBLE : View.GONE);
        if (!config.enabled) return;

        int size = dp(config.sizeDp);
        if (lp.width != size || lp.height != size) {
            lp.width = size;
            lp.height = size;
        }
        int availW = Math.max(0, root.getWidth() - size);
        int availH = Math.max(0, root.getHeight() - size);
        int newLeft = Math.round(Config.clamp(config.xRatio, 0f, 1f) * availW);
        int newTop = Math.round(Config.clamp(config.yRatio, 0f, 1f) * availH);
        boolean changed = lp.leftMargin != newLeft || lp.topMargin != newTop
                || lp.width != size || lp.height != size;
        lp.leftMargin = newLeft;
        lp.topMargin = newTop;
        if (changed) ball.setLayoutParams(lp);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                config = Config.Client.get(activity);
                if (!config.enabled) return false;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                baseLeft = lp.leftMargin;
                baseTop = lp.topMargin;
                dragging = false;
                longFired = false;
                ball.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start();
                if (config.longPressLock) {
                    handler.postDelayed(longPressRunnable, 520);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && !config.locked
                        && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true;
                    handler.removeCallbacks(longPressRunnable);
                }
                if (dragging) {
                    float maxX = Math.max(0, root.getWidth() - ball.getWidth());
                    float maxY = Math.max(0, root.getHeight() - ball.getHeight());
                    float newLeft = Config.clamp(baseLeft + dx, 0f, maxX);
                    float newTop = Config.clamp(baseTop + dy, 0f, maxY);
                    ball.setTranslationX(newLeft - baseLeft);
                    ball.setTranslationY(newTop - baseTop);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                ball.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                if (dragging) commitDrag();
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                ball.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                if (dragging) {
                    commitDrag();
                    return true;
                }
                if (longFired) return true;

                float upDx = event.getRawX() - downRawX;
                float upDy = event.getRawY() - downRawY;
                if (Math.hypot(upDx, upDy) < touchSlop * 1.2f) {
                    runAction(0);
                } else if (Math.abs(upDx) > Math.abs(upDy)) {
                    runAction(upDx > 0 ? 4 : 3);
                } else {
                    runAction(upDy > 0 ? 2 : 1);
                }
                return true;
        }
        return false;
    }

    private void commitDrag() {
        int availW = Math.max(0, root.getWidth() - ball.getWidth());
        int availH = Math.max(0, root.getHeight() - ball.getHeight());
        int left = Config.clamp(baseLeft + Math.round(ball.getTranslationX()), 0, availW);
        int top = Config.clamp(baseTop + Math.round(ball.getTranslationY()), 0, availH);

        ball.setTranslationX(0f);
        ball.setTranslationY(0f);
        lp.leftMargin = left;
        lp.topMargin = top;
        ball.setLayoutParams(lp);

        float xRatio = availW > 0 ? left / (float) availW : 0f;
        float yRatio = availH > 0 ? top / (float) availH : 0f;
        config.xRatio = xRatio;
        config.yRatio = yRatio;
        Config.Client.setPosition(activity, xRatio, yRatio);
    }

    private void runAction(int gestureIndex) {
        boolean answerShown = ActionExecutor.isAnswerShown(activity);
        String action = config.actionFor(answerShown, gestureIndex);
        if (Config.ACTION_NONE.equals(action)) return;

        boolean ok = ActionExecutor.execute(activity, action, ball);
        ball.flash(ok);
        if (ok && config.haptic) {
            ball.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        if (!ok) {
            ActionExecutor.toast(activity, "当前页面没有可用的「" + actionLabel(action) + "」控件");
        }
    }

    private String actionLabel(String key) {
        for (int i = 0; i < Config.ACTION_KEYS.length; i++) {
            if (Config.ACTION_KEYS[i].equals(key)) return Config.ACTION_LABELS[i];
        }
        return key;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
