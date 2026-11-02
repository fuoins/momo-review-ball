package com.fuoins.momoreviewball;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;
import android.view.InputDevice;
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
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 接管墨墨自带的复习悬浮球（{@code rev_floating_btn} / MMFloatingButton）。
 *
 * <p>接管方式分两层：
 * <ul>
 *   <li><b>手势</b>：在 {@code dispatchTouchEvent} 最前面判定。原生把「上/下/左/右滑动」
 *       写在事件分发里，MOVE 一旦被它认为成滑动就直接返回 true、连
 *       {@code OnTouchListener} 都不再通知，所以只能比它更早一步拿到事件：</li>
 *   <li><b>球面样式</b>：用控件自己的 {@code foreground} 承载，框架保证在所有子 View
 *       之后绘制，坐标和尺寸天然跟随球。</li>
 * </ul>
 *
 * <p>事件处理原则：不需要干预的事件原样放行（原生按压动画、长按拖动、边缘吸附全部保留），
 * 只在自己接管了某个手势时，向原生补发一个 {@code ACTION_CANCEL} 让它清理按压态，
 * 因此同一个手势不会既走模块又走原生。
 */
public final class FloatingButtonHook {

    private static final String CLASS_NAME = "com.maimemo.android.momo.ui.widget.button.MMFloatingButton";
    private static final String ID_NAME = "rev_floating_btn";

    private static final Map<View, TouchHandler> HOOKED = new WeakHashMap<>();

    /** 已经挂过 dispatchTouchEvent 的类，避免重复 hook。 */
    private static final Set<Class<?>> DISPATCH_HOOKED = new HashSet<>();

    /** 当前存活的球控件，用来在设置页拖动大小滑杆时即时改尺寸。 */
    private static final List<WeakReference<View>> ATTACHED = new ArrayList<>();

    private static volatile boolean sClassHooked;
    private static volatile boolean sInflateHooked;
    private static volatile boolean sVisibilityHooked;
    private static volatile boolean sFound;

    private FloatingButtonHook() {
    }

    public static void install(ClassLoader classLoader) {
        hookInflate();
        hookVisibility();
        hookClass(classLoader);
    }

    static boolean isFound() {
        return sFound;
    }

    /** 当前还在窗口里的球，优先取可见、有尺寸的那个。 */
    private static View liveBall() {
        View fallback = null;
        synchronized (ATTACHED) {
            for (Iterator<WeakReference<View>> it = ATTACHED.iterator(); it.hasNext(); ) {
                View item = it.next().get();
                if (item == null) {
                    it.remove();
                    continue;
                }
                if (item.getWindowToken() == null) continue;
                if (item.isShown() && item.getWidth() > 0 && item.getHeight() > 0) return item;
                if (fallback == null) fallback = item;
            }
        }
        return fallback;
    }

    /**
     * 把一整套「按下 - 滑动 - 抬起」原样交给墨墨自己的球处理。
     *
     * <p>墨墨的复习手势是写在球的 {@code dispatchTouchEvent} 里的（没有 OnTouchListener），
     * 所以只能在模块拦截层开一个「放行」口子，把合成事件喂给它——
     * 走的是应用自己的手势识别和动作分发，模块不复制任何业务逻辑。
     *
     * <p><b>方向以原生默认为准</b>：复习页「出现答案前」的原生球手势里，
     * 左滑＝瞄一眼（上滑＝发音、右滑＝返回上一个单词）。
     *
     * @param dir 1 上滑、2 下滑、3 左滑、4 右滑
     */
    static boolean replayNative(int dir) {
        View ball = liveBall();
        if (ball == null) {
            XposedLog.w("回放原生手势：当前没有存活的球");
            return false;
        }
        TouchHandler handler;
        synchronized (HOOKED) {
            handler = HOOKED.get(ball);
        }
        if (handler == null) {
            XposedLog.w("回放原生手势：球没有挂上处理器");
            return false;
        }
        return handler.replay(dir);
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

    /**
     * 监听所有控件的显隐。
     *
     * <p>「单词出现前 / 出现后」两套配置靠评分栏是否可见来切；之前是 800ms 轮询一次，
     * 答案出来以后球上的颜色和文字要等大半秒才变，观感就是「切换很慢」。
     * 评分栏显隐必然走 {@code View.setVisibility}，在这里接一下，命中后立刻重画。
     */
    private static void hookVisibility() {
        if (sVisibilityHooked) return;
        try {
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (BallOverlay.hasLive()) BallOverlay.requestRefresh();
                }
            });
            sVisibilityHooked = true;
            XposedLog.i("已监听控件显隐，答案出现前后即时切换");
        } catch (Throwable t) {
            XposedLog.w("hook View.setVisibility 失败，退回轮询", t);
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

    // ---------------------------------------------------------------- 事件分发

    /**
     * 从球控件开始往上找第一个自己重写了 {@code dispatchTouchEvent} 的类并挂上。
     *
     * <p>只挂最近的这一个：它如果是 {@code MMFloatingButton} 自己的重写，钩子就在
     * 原生判定手势之前执行；如果它没重写，那么往上必然是 {@code ViewGroup}，
     * 钩子同样在原生逻辑之前，只是会经过全局一次（内部用实例表过滤，代价极小）。
     */
    private static boolean ensureDispatchHook(Class<?> clazz) {
        synchronized (DISPATCH_HOOKED) {
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                Method method = null;
                try {
                    method = c.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
                } catch (NoSuchMethodException ignored) {
                }
                if (method == null) continue;
                if (DISPATCH_HOOKED.contains(c)) return true;
                try {
                    XposedBridge.hookMethod(method, DISPATCH_CALLBACK);
                    DISPATCH_HOOKED.add(c);
                    XposedLog.i("已接管事件分发：" + c.getName() + ".dispatchTouchEvent");
                    return true;
                } catch (Throwable t) {
                    XposedLog.w("hook " + c.getSimpleName() + ".dispatchTouchEvent 失败", t);
                }
            }
        }
        return false;
    }

    private static final XC_MethodHook DISPATCH_CALLBACK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!(param.thisObject instanceof View)) return;
            if (HOOKED.isEmpty()) return;
            View view = (View) param.thisObject;
            TouchHandler handler;
            synchronized (HOOKED) {
                handler = HOOKED.get(view);
            }
            if (handler == null) return;
            if (!(param.args[0] instanceof MotionEvent)) return;
            Boolean handled = handler.onDispatch(view, (MotionEvent) param.args[0]);
            if (handled != null) {
                param.setResult(handled);
            }
        }
    };

    // ---------------------------------------------------------------- 装配

    static void attach(final View view) {
        final TouchHandler handler;
        final BallOverlay overlay;
        synchronized (HOOKED) {
            if (HOOKED.containsKey(view)) return;
            overlay = new BallOverlay(view);
            handler = new TouchHandler(view, readTouchListener(view), overlay);
            HOOKED.put(view, handler);
        }

        // 球面样式：用控件自己的 foreground 承载。
        //   · 框架保证 foreground 在 dispatchDraw 之后画，一定盖在球体所有内容上面；
        //   · bounds 由控件在 onSizeChanged 里设置，尺寸天然跟随（448 还是 582 都对）；
        //   · 按压缩放、拖动位移都作用在同一个控件上，不需要任何坐标同步。
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
                    applyScale(v);
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

        // 手势必须在 dispatchTouchEvent 这一层接管：原生在分发阶段就把滑动吞了
        if (!ensureDispatchHook(view.getClass())) {
            view.setOnTouchListener(handler);
            XposedLog.w("事件分发接管失败，退回 OnTouchListener，原生滑动可能仍然优先");
        }

        sFound = true;
        XposedLog.i("已代理自带悬浮球，原有 listener=" + handler.original);

        // 尺寸：缩放整个球控件（连同挂在它上面的球面一起缩），
        // 这样球面和真球永远严丝合缝，不会出现「颜色比球大一圈」。
        synchronized (ATTACHED) {
            boolean known = false;
            for (Iterator<WeakReference<View>> it = ATTACHED.iterator(); it.hasNext(); ) {
                View item = it.next().get();
                if (item == null) it.remove();
                else if (item == view) known = true;
            }
            if (!known) ATTACHED.add(new WeakReference<>(view));
        }
        applyScale(view);
        view.post(() -> applyScale(view));

        // 球体几何诊断：控件本身比球大一倍，真实球的区域靠这段日志确认
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

    /** 设置页拖动大小滑杆时调用：把新比例应用到还活着的球上。 */
    static void applyScale() {
        synchronized (ATTACHED) {
            for (Iterator<WeakReference<View>> it = ATTACHED.iterator(); it.hasNext(); ) {
                View view = it.next().get();
                if (view == null) {
                    it.remove();
                } else {
                    applyScale(view);
                }
            }
        }
    }

    /** 以控件中心为轴缩放，位置和吸附结果都不会变。 */
    private static void applyScale(View view) {
        try {
            // 还没量出尺寸时设缩放会把轴心落在左上角，球会整体偏移；等布局完再来
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
            view.setPivotX(view.getWidth() / 2f);
            view.setPivotY(view.getHeight() / 2f);

            Config config = Config.get(view.getContext());
            float target = Config.clamp(config.scale, Config.SCALE_MIN, Config.SCALE_MAX);
            if (Math.abs(view.getScaleX() - target) < 0.001f
                    && Math.abs(view.getScaleY() - target) < 0.001f) {
                return;
            }
            view.setScaleX(target);
            view.setScaleY(target);
            XposedLog.i("悬浮球大小已应用：" + Math.round(target * 100) + "%");
        } catch (Throwable ignored) {
        }
    }

    /** 兜底：foreground 不可用时退回 ViewOverlay，尺寸每次绘制前强制对齐控件。 */
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

    // ---------------------------------------------------------------- 手势

    /**
     * 球的手势判定。
     *
     * <p>{@link #onDispatch} 返回 {@code null} 表示「不干预，原样交给墨墨」，
     * 返回 {@code true} 表示「这个事件模块吃掉了」。
     */
    static final class TouchHandler implements View.OnTouchListener {

        private final View view;
        private final View.OnTouchListener original;
        private final BallOverlay overlay;
        private final int slop;
        private final int longPressTimeout;

        private boolean tracking;
        private boolean swiping;
        private boolean dragging;
        /** 正在给原生补发 CANCEL，这一趟的分发要原样放行。 */
        private boolean injecting;

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

        Boolean onDispatch(View v, MotionEvent event) {
            if (injecting) return null;

            Config config = Config.get(v.getContext());
            if (!config.enabled) {
                if (tracking) reset();
                return null;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tracking = true;
                    swiping = false;
                    dragging = false;
                    downAt = event.getEventTime();
                    downX = event.getRawX();
                    downY = event.getRawY();
                    lastRawX = downX;
                    lastRawY = downY;
                    // 复习页的父容器（卡片 / ViewPager）会在 MOVE 阶段抢事件，
                    // 一抢球就收到 CANCEL，表现就是「滑动时好时坏、大多没反应」。
                    // 按下期间先把这个权利拿过来，抬手再还回去。
                    disallowParentIntercept(v, true);
                    // 锁定位置时连按下都不给原生，否则它会播长按拖动的反馈动画
                    return config.locked ? Boolean.TRUE : null;

                case MotionEvent.ACTION_MOVE: {
                    if (!tracking) return null;
                    lastRawX = event.getRawX();
                    lastRawY = event.getRawY();
                    float dx = lastRawX - downX;
                    float dy = lastRawY - downY;
                    float dist = (float) Math.hypot(dx, dy);

                    if (!swiping && !dragging && dist > slop) {
                        long held = event.getEventTime() - downAt;
                        if (!config.locked && held >= longPressTimeout) {
                            // 按住超过长按阈值才移动 = 墨墨自己的「长按拖动」，
                            // 位置、边界、吸附全部交还原生
                            dragging = true;
                        } else {
                            // 早期移动 = 模块手势。先给原生补一个 CANCEL 收掉
                            // 它已经开始的按压态，否则它会一边播动画一边等自己的 UP
                            swiping = true;
                            sendCancel(v, event);
                        }
                    }

                    if (dragging) {
                        overlay.setHighlight(0, 0f);
                        return null;
                    }
                    if (swiping) {
                        overlay.setHighlight(direction(dx, dy), dist / (slop * 4f));
                        return Boolean.TRUE;
                    }
                    return null;
                }

                case MotionEvent.ACTION_UP: {
                    if (!tracking) return null;
                    boolean wasDragging = dragging;
                    // 优先用抬手点算位移：快速滑动时 MOVE 采样可能只有一两个，
                    // 只信 lastRaw 会把一次明显的滑动判成点击。
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.hypot(lastRawX - downX, lastRawY - downY) > Math.hypot(dx, dy)) {
                        dx = lastRawX - downX;
                        dy = lastRawY - downY;
                    }
                    float dist = (float) Math.hypot(dx, dy);
                    reset();
                    overlay.setHighlight(0, 0f);

                    if (wasDragging) {
                        // 拖动结束，抬手交给原生做边缘吸附
                        return null;
                    }

                    // 拦下这次抬手：先给原生补一个 CANCEL 清理按压态，原生动作就不会触发
                    sendCancel(v, event);
                    if (dist <= slop * 1.2f) {
                        runAction(v, 0, config);
                    } else {
                        runAction(v, direction(dx, dy), config);
                    }
                    return Boolean.TRUE;
                }

                case MotionEvent.ACTION_CANCEL:
                    reset();
                    overlay.setHighlight(0, 0f);
                    return null;

                default:
                    return null;
            }
        }

        /** OnTouchListener 兜底路径（只在事件分发 hook 装不上时使用）。 */
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            Boolean handled = onDispatch(v, event);
            if (handled != null) return handled;
            return forward(v, event);
        }

        /** 原样交给墨墨：有原生 listener 就走它。 */
        private boolean forward(View v, MotionEvent event) {
            if (original != null) {
                try {
                    return original.onTouch(v, event);
                } catch (Throwable ignored) {
                }
            }
            return false;
        }

        /**
         * 给原生补发一个 CANCEL，清掉按下态 / 按压动画 / 未完成的拖动。
         *
         * <p>直接调 {@code dispatchTouchEvent}：原生把逻辑写在分发里，
         * 调 {@code onTouchEvent} 不一定能清干净。重入由 {@link #injecting} 放行。
         */
        private void sendCancel(View v, MotionEvent event) {
            if (injecting) return;
            injecting = true;
            MotionEvent cancel = null;
            try {
                cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                v.dispatchTouchEvent(cancel);
            } catch (Throwable ignored) {
            } finally {
                injecting = false;
                if (cancel != null) cancel.recycle();
            }
        }

        /**
         * 合成一次完整的滑动手势交给原生球。
         *
         * <p>{@link #injecting} 打开期间 {@link #onDispatch} 一律放行，
         * 所以这些事件会原封不动走到墨墨自己的 {@code dispatchTouchEvent}，
         * 由它判定方向并执行对应动作。
         *
         * <p>三处细节决定了「回放了但毫无反应」会不会发生：
         * <ul>
         *   <li><b>raw 坐标</b>：{@code MotionEvent.obtain} 造出来的事件 raw 等于局部坐标，
         *       原生若拿 {@code getRawX()} 判断「手指是不是在球上」，拿到的是屏幕左上角
         *       附近一个根本不存在的点，手势直接被丢掉。用 {@code offsetLocation}
         *       把 raw 偏移补成球在屏幕上的真实位置。</li>
         *   <li><b>事件来源</b>：标成触摸屏，避免原生按 source 过滤。</li>
         *   <li><b>投递对象</b>：先给容器，容器不接（返回 false）再直接喂原生
         *       {@code OnTouchListener}，覆盖手势挂在子 View 上的版本。</li>
         * </ul>
         */
        boolean replay(int dir) {
            int w = view.getWidth();
            int h = view.getHeight();
            if (w <= 0 || h <= 0) return false;

            float cx = w / 2f;
            float cy = h / 2f;
            // 真球只占控件中间一半，滑距按控件尺寸取，稳过原生的 slop
            float dist = Math.min(w, h) * 0.34f;
            float dx = 0f;
            float dy = 0f;
            switch (dir) {
                case 1:
                    dy = -dist;
                    break;
                case 2:
                    dy = dist;
                    break;
                case 3:
                    dx = -dist;
                    break;
                case 4:
                    dx = dist;
                    break;
                default:
                    return false;
            }

            int[] screen = new int[2];
            try {
                view.getLocationOnScreen(screen);
            } catch (Throwable ignored) {
            }

            long downAt = SystemClock.uptimeMillis();
            // 回放的是官方手势，官方可能带振动反馈；模块这边已经去掉振动，
            // 这里临时关掉控件的触感反馈，结束后原样恢复。
            boolean haptic = view.isHapticFeedbackEnabled();
            injecting = true;
            try {
                view.setHapticFeedbackEnabled(false);
                boolean consumed = sendSequence(true, downAt, cx, cy, dx, dy, screen);
                if (!consumed && original != null) {
                    // 容器没吃这串事件（手势听在子 View 或 listener 上），直接投给原生 listener
                    consumed = sendSequence(false, downAt, cx, cy, dx, dy, screen);
                    XposedLog.i("回放：容器未消费，已改投原生 OnTouchListener");
                }
                XposedLog.i("已回放原生球手势：方向=" + dir + "（" + dirName(dir) + "），滑距="
                        + Math.round(dist) + "，原生消费=" + consumed
                        + "，屏幕位置=(" + screen[0] + ',' + screen[1] + ')');
                return true;
            } catch (Throwable t) {
                XposedLog.w("回放原生球手势失败", t);
                return false;
            } finally {
                view.setHapticFeedbackEnabled(haptic);
                injecting = false;
            }
        }

        /**
         * 发一整串 DOWN → MOVE × n → UP。
         *
         * @param dispatch true 走 {@code dispatchTouchEvent}；false 直接调原生 listener
         * @return DOWN 是否被消费
         */
        private boolean sendSequence(boolean dispatch, long downAt, float cx, float cy,
                                     float dx, float dy, int[] screen) {
            int steps = 8;
            boolean downHandled = false;
            for (int i = 0; i <= steps + 1; i++) {
                long at = downAt + i * 16L;
                float ratio = Math.min(1f, i / (float) steps);
                float x = cx + dx * ratio;
                float y = cy + dy * ratio;
                int action = i == 0 ? MotionEvent.ACTION_DOWN
                        : (i > steps ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE);
                MotionEvent event = obtain(downAt, at, action, x, y, screen);
                boolean handled;
                try {
                    if (dispatch) {
                        handled = view.dispatchTouchEvent(event);
                    } else {
                        handled = original != null && original.onTouch(view, event);
                    }
                } catch (Throwable t) {
                    handled = false;
                } finally {
                    event.recycle();
                }
                if (i == 0) downHandled = handled;
            }
            return downHandled;
        }

        /**
         * 造一个「看起来像真手指」的事件。
         *
         * <p>{@code obtain(...)} 里的 x/y 是控件内坐标，raw 默认与之相同；
         * 调 {@code offsetLocation(屏幕左上角)} 后 raw 变成屏幕坐标，而 x/y 不变，
         * 和真实触摸事件的结构完全一致。
         */
        private MotionEvent obtain(long downAt, long at, int action,
                                   float x, float y, int[] screen) {
            MotionEvent event = MotionEvent.obtain(downAt, at, action, x, y, 0);
            event.offsetLocation(screen[0], screen[1]);
            try {
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            } catch (Throwable ignored) {
            }
            return event;
        }

        private String dirName(int dir) {
            switch (dir) {
                case 1:
                    return "上滑";
                case 2:
                    return "下滑";
                case 3:
                    return "左滑";
                case 4:
                    return "右滑";
                default:
                    return "未知";
            }
        }

        private void reset() {
            tracking = false;
            swiping = false;
            dragging = false;
            disallowParentIntercept(view, false);
            // 原生的按压/回弹动画如果改的是 scale，播完会把自定义大小顶掉；
            // 等它结束再按回去，两次是为了兼容动画时长不同的版本
            view.postDelayed(() -> applyScale(view), 320L);
            view.postDelayed(() -> applyScale(view), 900L);
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
                overlay.kick();
                return;
            }
            boolean ok = ActionExecutor.execute(activity, action, v);
            XposedLog.d("gesture=" + Config.GESTURE_LABELS[gestureIndex]
                    + " 已显示答案=" + answerShown + " 动作=" + action + " 执行=" + ok);
            // 刚点完/滑完往往就是评分栏出现或消失的时机，多复查几次让球马上换配色
            overlay.kick();

            if (!ok) {
                if (Config.ACTION_FAMILIAR.equals(action)) {
                    // 「熟知」不是默认按钮，点不到时直接把开关路径告诉用户
                    ActionExecutor.toast(activity,
                            "「熟知」需要先在 更多设置 → 复习页面 → 工具栏快捷 打开「标识熟知」");
                } else if (Config.ACTION_PEEK.equals(action)) {
                    // 瞄一眼走的是官方按钮 / 原生球手势，三路都没走通才提示
                    ActionExecutor.toast(activity,
                            "「瞄一眼」没有响应：当前页面没有可用的官方入口");
                } else {
                    ActionExecutor.toast(activity, "当前页面没有可用的「" + label(action) + "」控件");
                }
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
