package com.fuoins.momoreviewball;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;

import org.xmlpull.v1.XmlPullParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 接管墨墨自带的复习悬浮球（{@code rev_floating_btn} / MMFloatingButton）。
 *
 * <p>接管方式是「代理」而不是「替换」：
 * <ul>
 *   <li>所有触摸事件默认原样转发给墨墨自己的监听器 / {@code onTouchEvent}，
 *       所以自带的按压动画、拖动跟手、边界吸附、长按拖动全部保持原样；</li>
 *   <li>只有在手指抬起（{@code ACTION_UP}）且这一轮没有走「长按拖动」时，
 *       才拦下这次抬手，按模块配置执行动作，并向原生补发一个
 *       {@code ACTION_CANCEL} 让控件清理按压态——原生因此不会重复执行它自己的手势。</li>
 * </ul>
 *
 * <p>定位方式有两路，互为兜底：
 * <ol>
 *   <li>Hook {@code LayoutInflater.inflate}，在返回的视图树里按 id 名找控件——不依赖类名，
 *       加固应用也能命中；</li>
 *   <li>Hook {@code MMFloatingButton} 的构造方法（类能被 classLoader 加载时才生效）。</li>
 * </ol>
 */
public final class FloatingButtonHook {

    private static final String TAG = "MoMoBall";
    private static final String CLASS_NAME = "com.maimemo.android.momo.ui.widget.button.MMFloatingButton";
    private static final String ID_NAME = "rev_floating_btn";

    private static final Map<View, TouchHandler> HOOKED = new WeakHashMap<>();

    /** 每个球实例对应的绘制器，供 draw() hook 取用。 */
    private static final Map<View, BallOverlay> PAINTERS = new WeakHashMap<>();

    private static volatile boolean sClassHooked;
    private static volatile boolean sInflateHooked;
    private static volatile boolean sDrawHooked;
    private static volatile boolean sFound;

    private FloatingButtonHook() {
    }

    public static void install(ClassLoader classLoader) {
        hookInflate();
        hookClass(classLoader);
    }

    static boolean isFound() {
        return sFound;
    }

    private static void hookInflate() {
        if (sInflateHooked) return;
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    XmlPullParser.class, ViewGroup.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (result instanceof View) {
                                scan((View) result);
                            }
                        }
                    });
            sInflateHooked = true;
            XposedLog.i("已监听布局加载");
        } catch (Throwable t) {
            XposedLog.e("hook inflate 失败", t);
        }
    }

    private static void scan(View root) {
        try {
            android.content.Context context = root.getContext();
            if (context == null) return;
            int id = context.getResources().getIdentifier(ID_NAME, "id", context.getPackageName());
            if (id == 0) return;
            View button = root.findViewById(id);
            if (button != null) attach(button);
        } catch (Throwable ignored) {
        }
    }

    private static void hookClass(ClassLoader classLoader) {
        if (sClassHooked) return;
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(CLASS_NAME, classLoader);
            if (clazz == null) {
                XposedLog.w("classLoader 暂未加载 " + CLASS_NAME + "，等待布局探测");
                return;
            }
            for (final Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View) {
                                final View view = (View) param.thisObject;
                                view.post(() -> attach(view));
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            sClassHooked = true;
            XposedLog.i("已 hook " + CLASS_NAME + " 构造");
        } catch (Throwable t) {
            XposedLog.e("hook 悬浮球类失败", t);
        }
    }

    /**
     * 在「真正自绘的那个 View」的绘制方法末尾补画球面标识。
     *
     * <p>实测 {@code MMFloatingButton} 自己既没重写 {@code draw()} 也没重写 {@code onDraw()}，
     * 它只是个容器，球是里面那个 224×224 的子 View。所以不能死盯球控件本身，
     * 要顺着树往下找到第一个自己重写了绘制方法的子 View，把画布接在它身上：
     * 坐标系就是那颗球的坐标系，层级也一定在球的内容之上。
     */
    private static final String[] PAINT_METHODS = {"draw", "dispatchDraw", "onDraw"};

    /** 已经 hook 过的类，避免同一个类被重复挂。 */
    private static final java.util.Set<Class<?>> HOOKED_CLASSES = new java.util.HashSet<>();

    private static Method pickPaintMethod(Class<?> clazz) {
        for (String name : PAINT_METHODS) {
            try {
                return clazz.getDeclaredMethod(name, Canvas.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** 从球控件往下找第一个「自己重写了绘制方法、且面积够大」的 View。 */
    private static View findPaintTarget(View root) {
        if (pickPaintMethod(root.getClass()) != null) return root;
        if (!(root instanceof ViewGroup)) return null;

        int minSide = Math.max(1, Math.min(root.getWidth(), root.getHeight()) * 3 / 10);
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            queue.add(group.getChildAt(i));
        }
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 64) {
            View v = queue.poll();
            if (v.getWidth() <= 0 || v.getHeight() <= 0) continue;
            if (Math.min(v.getWidth(), v.getHeight()) < minSide) continue;
            if (pickPaintMethod(v.getClass()) != null) return v;
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    queue.add(vg.getChildAt(i));
                }
            }
        }
        return null;
    }

    private static final XC_MethodHook DRAW_CALLBACK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!(param.thisObject instanceof View)) return;
            View view = (View) param.thisObject;
            BallOverlay painter;
            synchronized (PAINTERS) {
                painter = PAINTERS.get(view);
            }
            if (painter == null) return;
            if (!(param.args[0] instanceof Canvas)) return;
            int w = view.getWidth();
            int h = view.getHeight();
            if (w <= 0 || h <= 0) return;
            try {
                painter.setBounds(0, 0, w, h);
                Canvas canvas = (Canvas) param.args[0];
                int save = canvas.save();
                painter.draw(canvas);
                canvas.restoreToCount(save);
            } catch (Throwable ignored) {
            }
        }
    };

    /** @return true 表示已经找到并挂上绘制挂点。 */
    private static boolean tryHookPaint(View ball, BallOverlay painter) {
        try {
            View target = findPaintTarget(ball);
            if (target == null) return false;
            Class<?> clazz = target.getClass();
            Method method = pickPaintMethod(clazz);
            if (method == null) return false;

            synchronized (PAINTERS) {
                PAINTERS.put(target, painter);
            }
            painter.setPaintHost(target);

            synchronized (HOOKED_CLASSES) {
                if (!HOOKED_CLASSES.contains(clazz)) {
                    XposedBridge.hookMethod(method, DRAW_CALLBACK);
                    HOOKED_CLASSES.add(clazz);
                }
            }
            if (!sDrawHooked) {
                sDrawHooked = true;
                XposedLog.i("球面绘制挂点=" + clazz.getSimpleName() + "." + method.getName()
                        + "，尺寸=" + target.getWidth() + "x" + target.getHeight());
            } else {
                XposedLog.i("新球实例已绑定绘制挂点 " + clazz.getSimpleName()
                        + "，尺寸=" + target.getWidth() + "x" + target.getHeight());
            }
            return true;
        } catch (Throwable t) {
            XposedLog.w("挂绘制点失败", t);
            return false;
        }
    }

    static void attach(final View view) {
        final View.OnTouchListener original;
        final TouchHandler handler;
        final BallOverlay overlay;
        synchronized (HOOKED) {
            if (HOOKED.containsKey(view)) return;
            original = readTouchListener(view);
            overlay = new BallOverlay(view);
            handler = new TouchHandler(view, original, overlay);
            HOOKED.put(view, handler);
            synchronized (PAINTERS) {
                PAINTERS.put(view, overlay);
            }
        }
        view.setOnTouchListener(handler);

        // 球面标识：沿轮廓的四向色带 + 中心动作文字。
        // 首选「在球自己的 draw() 之后补画」——坐标系天然正确、层级一定在最上；
        // 类没重写 draw/onDraw 时才退回父容器里的覆盖 View，再不行退回 ViewOverlay。
        // 球面标识：沿轮廓的四向色带 + 中心动作文字。
        //
        // 这里改用控件自己的 foreground 承载，而不是 overlay / 兄弟 View / 绘制 hook：
        //   · 框架保证 foreground 在 dispatchDraw 之后画，一定盖在球体所有内容上面；
        //   · bounds 由控件在 onSizeChanged 里设置，尺寸天然跟随（448 还是 582 都对）；
        //   · 按压缩放、拖动位移都作用在同一个控件上，不需要任何坐标同步；
        //   · setForeground 会自动把 drawable 的 callback 指向控件，
        //     invalidateSelf() 就能直接触发重绘。
        try {
            Drawable old = null;
            try {
                old = view.getForeground();
            } catch (Throwable ignored) {
            }
            overlay.setBounds(0, 0, Math.max(view.getWidth(), 1), Math.max(view.getHeight(), 1));
            if (old == null) {
                view.setForeground(overlay);
            } else {
                // 原生自己有 foreground 的场合，叠一层，别把原本的效果吃掉
                view.setForeground(new LayerDrawable(new Drawable[]{old, overlay}));
            }
            overlay.start();
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    overlay.start();
                    v.invalidate();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    overlay.stop();
                }
            });
            XposedLog.i("球面标识已挂为 foreground，控件=" + view.getWidth() + "x" + view.getHeight()
                    + "，原有 foreground=" + old);
        } catch (Throwable t) {
            XposedLog.w("挂 foreground 失败，退回 overlay", t);
            attachOverlay(view, overlay);
        }

        sFound = true;
        XposedLog.i("已代理自带悬浮球，原有 listener=" + original);

        // 球体几何诊断：控件本身不是正方形，真实球的区域靠这段日志确认
        view.post(() -> {
            StringBuilder sb = new StringBuilder("球控件尺寸 ");
            sb.append(view.getWidth()).append('x').append(view.getHeight());
            sb.append("，背景=").append(view.getBackground());
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                sb.append("，子View=").append(group.getChildCount());
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    sb.append(" [").append(i).append(']')
                            .append(child.getClass().getSimpleName()).append(' ')
                            .append(child.getWidth()).append('x').append(child.getHeight())
                            .append(" @(").append(child.getLeft()).append(',')
                            .append(child.getTop()).append(')');
                }
            }
            XposedLog.i(sb.toString());
        });

        // 复习页一出现就把日志落盘，设置页注入失败时也能从文件里拿到
        view.postDelayed(() -> Diag.flush(view.getContext()), 1500L);
    }

    /** 兜底：父容器不可用时退回 ViewOverlay，尺寸每次绘制前强制对齐控件。 */
    private static void attachOverlay(final View view, final BallOverlay overlay) {
        try {
            view.getOverlay().add(overlay);
            overlay.start();
            view.addOnLayoutChangeListener((v, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) ->
                    overlay.setBounds(0, 0, v.getWidth(), v.getHeight()));
            view.post(() -> {
                overlay.setBounds(0, 0, view.getWidth(), view.getHeight());
                XposedLog.i("已改用 overlay 承载球面标识，范围 " + view.getWidth() + "x" + view.getHeight());
            });
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    overlay.setBounds(0, 0, v.getWidth(), v.getHeight());
                    overlay.start();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    overlay.stop();
                }
            });
        } catch (Throwable t) {
            XposedLog.w("overlay 兜底也失败", t);
        }
    }

    private static View.OnTouchListener readTouchListener(View view) {
        try {
            Field field = View.class.getDeclaredField("mOnTouchListener");
            field.setAccessible(true);
            return (View.OnTouchListener) field.get(view);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 代理自带悬浮球的触摸：默认全转发，只在抬手时接管手势判定。 */
    static final class TouchHandler implements View.OnTouchListener {

        private final View view;
        private final View.OnTouchListener original;
        private final BallOverlay overlay;
        private final int slop;
        private final int longPressTimeout;

        private boolean tracking;
        private boolean dragging;
        private long downAt;
        private float downX;
        private float downY;
        private float lastRawX;
        private float lastRawY;

        TouchHandler(View view, View.OnTouchListener original, BallOverlay overlay) {
            this.view = view;
            this.original = original;
            this.overlay = overlay;
            ViewConfiguration vc = ViewConfiguration.get(view.getContext());
            this.slop = Math.max(vc.getScaledTouchSlop(), 1);
            this.longPressTimeout = ViewConfiguration.getLongPressTimeout();
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            Config config = Config.get(v.getContext());
            if (!config.enabled) {
                return forward(v, event);
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tracking = true;
                    dragging = false;
                    downAt = SystemClock.uptimeMillis();
                    downX = event.getRawX();
                    downY = event.getRawY();
                    lastRawX = downX;
                    lastRawY = downY;
                    // 复习页的父容器（卡片 / ViewPager）会在 MOVE 阶段抢事件，
                    // 一抢球就收到 CANCEL，表现就是「滑动时好时坏、大多没反应」。
                    // 按下期间先把这个权利拿过来，抬手再还回去。
                    disallowParentIntercept(v, true);
                    // 锁定位置时连按下都不给原生，否则它会播长按拖动的反馈动画
                    if (config.locked) return true;
                    return forward(v, event);

                case MotionEvent.ACTION_MOVE: {
                    if (!tracking) return forward(v, event);
                    lastRawX = event.getRawX();
                    lastRawY = event.getRawY();
                    float dx = lastRawX - downX;
                    float dy = lastRawY - downY;
                    float dist = (float) Math.hypot(dx, dy);

                    // 按住超过长按阈值后才开始移动 = 墨墨自己的「长按拖动」
                    // 位置、边界、吸附全部交还原生，模块不参与，避免把球拖出屏幕
                    if (!dragging && !config.locked
                            && SystemClock.uptimeMillis() - downAt >= longPressTimeout
                            && dist > slop) {
                        dragging = true;
                    }

                    if (dragging) {
                        overlay.setHighlight(0, 0f);
                        return forward(v, event);
                    }

                    // 模块自己的手势动画：滑动中高亮对应方向，并显示该方向将要执行的动作
                    if (dist > slop * 0.7f) {
                        overlay.setHighlight(direction(dx, dy), dist / (slop * 4f));
                    }

                    // 直接吞掉，不转发也不钉坐标。
                    // 转发会被原生抢先判定手势（时好时坏）；钉坐标会让原生 onTouchEvent
                    // 认为没有位移而返回 false，触摸序列被父容器取消，抬手事件收不到，
                    // 于是只剩点击能用。吞掉则自己保证序列连续，原生完全不知道手指动过。
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    if (!tracking) return forward(v, event);
                    boolean wasDragging = dragging;
                    // 优先用抬手点算位移：快速滑动时 MOVE 采样可能只有一两个，
                    // 只信 lastRaw 会把一次明显的滑动判成点击。
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.hypot(lastRawX - downX, lastRawY - downY) > Math.hypot(dx, dy)) {
                        dx = lastRawX - downX;
                        dy = lastRawY - downY;
                    }
                    reset();
                    overlay.setHighlight(0, 0f);

                    if (wasDragging) {
                        // 拖动结束，抬手交给原生做边缘吸附
                        return forward(v, event);
                    }

                    // 拦下这次抬手：先给原生补一个 CANCEL 清理按压态，原生动作就不会触发
                    if (!config.locked) cancelNative(v, event);
                    float dist = (float) Math.hypot(dx, dy);
                    if (dist <= slop * 1.2f) {
                        runAction(v, 0, config);
                    } else {
                        runAction(v, direction(dx, dy), config);
                    }
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    reset();
                    overlay.setHighlight(0, 0f);
                    return forward(v, event);

                default:
                    return forward(v, event);
            }
        }

        /** 原样交给墨墨：有原生 listener 就走它，没有则返回 false 让 View.onTouchEvent 处理。 */
        private boolean forward(View v, MotionEvent event) {
            if (original != null) {
                try {
                    return original.onTouch(v, event);
                } catch (Throwable ignored) {
                }
            }
            return false;
        }

        /** 给原生补发 CANCEL，清掉按下态 / 正在播放的按压动画，同时通知它手势结束。 */
        private void cancelNative(View v, MotionEvent event) {
            MotionEvent cancel = null;
            try {
                cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                if (original != null) original.onTouch(v, cancel);
                v.onTouchEvent(cancel);
            } catch (Throwable ignored) {
            } finally {
                if (cancel != null) cancel.recycle();
            }
        }

        private void reset() {
            tracking = false;
            dragging = false;
            disallowParentIntercept(view, false);
        }

        /** 临时把「父容器不得抢事件」的权利拿到手，抬手就还回去。 */
        private void disallowParentIntercept(View v, boolean disallow) {
            try {
                ViewParent parent = v.getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
            } catch (Throwable ignored) {
            }
        }

        private int direction(float dx, float dy) {
            if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? 4 : 3;
            return dy > 0 ? 2 : 1;
        }

        private void runAction(View v, int gestureIndex, Config config) {
            Activity activity = Util.findActivity(v.getContext());
            if (activity == null) return;

            boolean answerShown = ActionExecutor.isAnswerShown(activity);
            String action = config.actionFor(answerShown, gestureIndex);
            if (Config.ACTION_NONE.equals(action)) {
                XposedLog.d("gesture=" + Config.GESTURE_LABELS[gestureIndex]
                        + " 已显示答案=" + answerShown + " 动作=未配置");
                return;
            }
            boolean ok = ActionExecutor.execute(activity, action, v);
            XposedLog.d("gesture=" + Config.GESTURE_LABELS[gestureIndex]
                    + " 已显示答案=" + answerShown + " 动作=" + action + " 执行=" + ok);

            if (ok) {
                if (config.haptic) {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
            } else {
                ActionExecutor.toast(activity, "当前页面没有可用的「" + label(action) + "」控件");
            }
        }

        private String label(String key) {
            for (int i = 0; i < Config.ACTION_KEYS.length; i++) {
                if (Config.ACTION_KEYS[i].equals(key)) return Config.ACTION_LABELS[i];
            }
            return key;
        }
    }
}
