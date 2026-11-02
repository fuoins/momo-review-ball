package com.fuoins.momoreviewball;

import android.app.Activity;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;
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
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 把悬浮球挂到 Activity 的 content 视图上，处理拖动与手势。
 *
 * <p>墨墨 5.6.00 起复习页不再是独立 Activity（清单里只有 cmp.MomoCmpEntryActivity，
 * 复习内容是 layout/fragment_review 这个 Fragment），因此不能靠 Activity 生命周期判断复习页。
 * 这里改为：在承载 Activity 上常驻一个球，周期性检查复习页专属控件是否真实可见，
 * 可见则显示、否则隐藏。5.5.x 的独立 ReviewActivity 架构同样适用。
 */
public final class BallController implements View.OnTouchListener {

    private static final String TAG = "MoMoBall";

    private static final Map<Activity, BallController> ATTACHED = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 复习页特征控件，命中任意一个（且真实可见）即认为是复习页。 */
    private static final String[] FEATURE_IDS = {
            "rev_cl_operation_bar", "rev_familiar", "rev_vague", "rev_forget",
            "cl_content_hint", "tv_back2", "rev_play_spelling"
    };
    private static final String[] FEATURE_TEXTS = {
            "点击屏幕显示答案", "请回忆", "瞄一眼", "返回上一个单词"
    };
    /** content 视图尚未就绪时的重试间隔。 */
    private static final long[] ATTACH_RETRY = {120, 200, 320, 500, 800};
    /** 复习页检测间隔：已显示时更密，隐藏时放宽。 */
    private static final long WATCH_VISIBLE_MS = 700L;
    private static final long WATCH_HIDDEN_MS = 1000L;

    private static volatile int[] sFeatureIds;
    private static volatile boolean sToastShown;

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

    private volatile boolean released;
    private boolean onReviewScreen;
    private int watchTick;

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

    private final Runnable diagRunnable = new Runnable() {
        @Override
        public void run() {
            String info = diagnostics();
            Log.i(TAG, info);
            XposedLog.i(info);
            ActionExecutor.toast(activity, "诊断信息已写入日志（tag: MoMoBall）");
        }
    };

    private final Runnable watchRunnable = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            watchTick++;
            boolean review = isReviewByControls(activity);
            // 控件探测失败时，每隔若干次再做一次文案兜底，避免高频遍历视图树。
            if (!review && watchTick % 8 == 0) {
                review = hasReviewText(activity);
            }
            if (review != onReviewScreen) {
                onReviewScreen = review;
                Log.i(TAG, "复习页状态 = " + review + " (" + activity.getClass().getName() + ")");
                if (review) {
                    // 墨墨自带的悬浮球也在 content 里，进入复习页时把自己的球提到最上层。
                    try {
                        ball.bringToFront();
                    } catch (Throwable ignored) {
                    }
                    maybeToastLoaded();
                }
            }
            if (ball.getParent() == null) {
                try {
                    root.addView(ball, lp);
                    Log.i(TAG, "悬浮球被移除，已重新挂载");
                } catch (Throwable t) {
                    Log.w(TAG, "重新挂载失败", t);
                }
            }
            updateVisibility();
            handler.postDelayed(this, review ? WATCH_VISIBLE_MS : WATCH_HIDDEN_MS);
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
        this.ball.setVisibility(View.GONE);

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

    /**
     * 在给定 Activity 上准备悬浮球。可被 resume / focus 多次调用，内部幂等。
     *
     * <p>不做「是否复习页」的前置判断——页面可能是同一个 Activity 内的 Fragment 切换，
     * 直接挂上后由 watcher 控制显隐。
     */
    public static void maybeAttach(Activity activity, String source) {
        if (activity == null || isDead(activity)) return;

        synchronized (ATTACHED) {
            BallController existing = ATTACHED.get(activity);
            if (existing != null) {
                existing.scheduleRefresh();
                return;
            }
        }

        Runnable task = () -> attachNow(activity, source, 0);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN.post(task);
        }
    }

    private static void attachNow(Activity activity, String source, int attempt) {
        if (isDead(activity)) return;
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(activity)) return;
        }

        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) {
            if (attempt < ATTACH_RETRY.length) {
                long delay = ATTACH_RETRY[attempt];
                MAIN.postDelayed(() -> attachNow(activity, source, attempt + 1), delay);
            } else {
                Log.w(TAG, "content 不可用: " + content + " activity=" + activity.getClass().getName());
            }
            return;
        }

        try {
            BallController controller = new BallController(activity, (FrameLayout) content);
            synchronized (ATTACHED) {
                ATTACHED.put(activity, controller);
            }
            controller.ball.post(controller::applyConfig);
            controller.handler.post(controller.watchRunnable);
            Log.i(TAG, "已挂载: " + activity.getClass().getName() + " (source=" + source + ")");
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
            XposedLog.e("attach 失败", t);
        }
    }

    private static boolean isDead(Activity activity) {
        try {
            if (activity.isFinishing()) return true;
            return android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 按控件 id 判断当前是否处于复习页，要求控件真实可见（排除 ViewPager 预加载的隐藏页）。 */
    private static boolean isReviewByControls(Activity activity) {
        try {
            int[] ids = featureIds(activity.getResources(), activity.getPackageName());
            for (int id : ids) {
                if (id == 0) continue;
                View v = activity.findViewById(id);
                if (visibleOnScreen(v)) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "控件探测失败", t);
        }
        return false;
    }

    /** 不仅要求 isShown()，还要在屏幕上占实际区域，排除被移出屏幕的预加载页面。 */
    private static boolean visibleOnScreen(View v) {
        if (v == null || !v.isShown()) return false;
        Rect rect = new Rect();
        if (!v.getGlobalVisibleRect(rect)) return false;
        return rect.width() > 0 && rect.height() > 0;
    }

    private static int[] featureIds(Resources res, String pkg) {
        int[] ids = sFeatureIds;
        if (ids != null) return ids;
        ids = new int[FEATURE_IDS.length];
        for (int i = 0; i < FEATURE_IDS.length; i++) {
            try {
                ids[i] = res.getIdentifier(FEATURE_IDS[i], "id", pkg);
            } catch (Throwable ignored) {
                ids[i] = 0;
            }
        }
        sFeatureIds = ids;
        Log.i(TAG, "控件 id 解析: " + java.util.Arrays.toString(ids));
        return ids;
    }

    private static boolean hasReviewText(Activity activity) {
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return false;
            return walkForText(decor, 0);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean walkForText(View view, int depth) {
        if (view == null || depth > 40) return false;
        if (view.getVisibility() != View.VISIBLE) return false;

        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (matchesReviewText(text)) return true;
        }
        if (matchesReviewText(view.getContentDescription())) return true;

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                if (walkForText(group.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    private static boolean matchesReviewText(CharSequence text) {
        if (text == null) return false;
        String s = text.toString();
        if (s.isEmpty()) return false;
        for (String key : FEATURE_TEXTS) {
            if (s.contains(key)) return true;
        }
        return false;
    }

    private void maybeToastLoaded() {
        if (sToastShown || !config.diagnose) return;
        sToastShown = true;
        ActionExecutor.toast(activity, "复习悬浮球已生效");
    }

    private void updateVisibility() {
        boolean visible = config.enabled && onReviewScreen;
        int target = visible ? View.VISIBLE : View.GONE;
        if (ball.getVisibility() != target) ball.setVisibility(target);
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            Config.Client.invalidate();
            applyConfig();
        }
    };

    public static void detach(Activity activity) {
        if (activity == null) return;
        BallController controller;
        synchronized (ATTACHED) {
            controller = ATTACHED.remove(activity);
        }
        if (controller != null) controller.release();
    }

    private void release() {
        released = true;
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
        updateVisibility();
        if (!config.enabled) return;
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return;

        int size = dp(config.sizeDp);
        if (lp.width != size || lp.height != size) {
            lp.width = size;
            lp.height = size;
        }
        int availW = Math.max(0, root.getWidth() - size);
        int availH = Math.max(0, root.getHeight() - size);
        int newLeft = Math.round(Config.clamp(config.xRatio, 0f, 1f) * availW);
        int newTop = Math.round(Config.clamp(config.yRatio, 0f, 1f) * availH);
        boolean changed = lp.leftMargin != newLeft || lp.topMargin != newTop;
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
                if (config.diagnose) {
                    handler.postDelayed(diagRunnable, 3000);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && !config.locked
                        && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true;
                    handler.removeCallbacks(longPressRunnable);
                    handler.removeCallbacks(diagRunnable);
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
                handler.removeCallbacks(diagRunnable);
                ball.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                if (dragging) commitDrag();
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                handler.removeCallbacks(diagRunnable);
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

    private String diagnostics() {
        return "MoMoBall 诊断\n"
                + "activity=" + activity.getClass().getName() + "\n"
                + "reviewScreen=" + onReviewScreen + "\n"
                + "answerShown=" + ActionExecutor.isAnswerShown(activity) + "\n"
                + "locked=" + config.locked + " enabled=" + config.enabled + "\n"
                + "configSource=" + Config.Client.describeSource() + "\n"
                + "可见控件=" + ActionExecutor.dumpTexts(activity);
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
