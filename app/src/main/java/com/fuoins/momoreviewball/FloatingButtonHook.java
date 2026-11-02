package com.fuoins.momoreviewball;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
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
            hookFloatBtnListener(clazz);
        } catch (Throwable t) {
            XposedLog.e("hook 悬浮球类失败", t);
        }
    }

    /** 球的点击/滑动手势最终是回调给这个 listener 的，抓住它就能直接触发官方动作。 */
    private static volatile Object sFloatBtnListener;

    private static void hookFloatBtnListener(Class<?> clazz) {
        try {
            Method target = null;
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals("setOnFloatBtnClickListener")
                        && method.getParameterTypes().length == 1) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                XposedLog.w("没找到 setOnFloatBtnClickListener");
                return;
            }
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length != 1 || param.args[0] == null) return;
                    Object listener = param.args[0];
                    if (listener == sFloatBtnListener) return;
                    sFloatBtnListener = listener;
                    dumpListener(listener);
                    hookGestureCallback(listener);
                }
            });
            XposedLog.i("已监听球的 listener 注册");
        } catch (Throwable t) {
            XposedLog.w("hook setOnFloatBtnClickListener 失败", t);
        }
    }

    private static void dumpListener(Object listener) {
        Class<?> clazz = listener.getClass();
        StringBuilder sb = new StringBuilder("球的手势回调 ").append(clazz.getName()).append(" 方法：");
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                sb.append(method.getName()).append('(');
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(params[i].getSimpleName());
                }
                sb.append(") ");
            }
        } catch (Throwable ignored) {
        }
        sb.append("；字段：");
        try {
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                sb.append(field.getName()).append('=');
                try {
                    sb.append(field.get(listener));
                } catch (Throwable t) {
                    sb.append('?');
                }
                sb.append(' ');
            }
        } catch (Throwable ignored) {
        }
        XposedLog.i(sb.toString());
    }

    /** 官方手势回调本身也监听一下：方向常量到底传的几，一眼就能看到。 */
    private static final Set<Class<?>> LISTENER_HOOKED = new HashSet<>();

    private static void hookGestureCallback(Object listener) {
        Class<?> clazz = listener.getClass();
        synchronized (LISTENER_HOOKED) {
            if (!LISTENER_HOOKED.add(clazz)) return;
        }
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != int.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedLog.i("官方手势回调被调用："
                                + param.method.getName() + "(" + param.args[0] + ")");
                    }
                });
                XposedLog.i("已监听官方手势回调 " + method.getName() + "(int)");
            }
        } catch (Throwable t) {
            XposedLog.w("监听官方手势回调失败", t);
        }
    }

    /**
     * 直调墨墨注册给球的手势回调。
     *
     * <p>日志实测这个回调是 {@code z49.a(int)}：一个 int 就是手势方向。
     * 资源里「发音，瞄一眼、返回上一个单词」正好三个动作，按 上/左/右 对应 1/2/3，
     * 所以左滑（瞄一眼）先按 2 试。
     */
    static boolean callFloatBtnListener(int code) {
        Object listener = sFloatBtnListener;
        if (listener == null) {
            XposedLog.w("官方手势回调还没注册，无法直调");
            return false;
        }
        try {
            for (Method method : listener.getClass().getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != int.class) continue;
                method.setAccessible(true);
                method.invoke(listener, code);
                XposedLog.i("已直调官方手势回调 " + method.getName() + "(" + code + ")");
                return true;
            }
            XposedLog.w("官方手势回调里没有 (int) 方法");
        } catch (Throwable t) {
            XposedLog.w("直调官方手势回调失败", t);
        }
        return false;
    }

    static Object floatBtnListener() {
        return sFloatBtnListener;
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
            dumpTouchChain(view);
            dumpMethods(view);
        });

        // 复习页一出现就把日志落盘，设置页注入失败时也能从文件里拿到
        view.postDelayed(() -> Diag.flush(view.getContext()), 1500L);
    }

    /**
     * 触摸链诊断：球的每一层祖先是否自己处理触摸、有没有 listener、Activity 是否接管。
     *
     * <p>实测球的 {@code dispatchTouchEvent} 返回 false、{@code OnTouchListener} 为 null，
     * 说明手势根本不在球控件里。这条链用来找出真正接手势的那一层。
     */
    private static void dumpTouchChain(View view) {
        StringBuilder sb = new StringBuilder("触摸链（从球往上）：");
        View current = view;
        for (int i = 0; i < 8 && current != null; i++) {
            sb.append("\n  [").append(i).append("] ").append(current.getClass().getName())
                    .append(' ').append(current.getWidth()).append('x').append(current.getHeight())
                    .append(" clickable=").append(current.isClickable())
                    .append(" listener=").append(readTouchListener(current) != null)
                    .append(" dispatch=").append(declares(current.getClass(), "dispatchTouchEvent"))
                    .append(" intercept=").append(declares(current.getClass(), "onInterceptTouchEvent"))
                    .append(" touch=").append(declares(current.getClass(), "onTouchEvent"));
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        try {
            Activity activity = Util.findActivity(view.getContext());
            if (activity != null) {
                sb.append("\n  Activity ").append(activity.getClass().getName())
                        .append(" dispatch=").append(declares(activity.getClass(), "dispatchTouchEvent"))
                        .append(" keyDown=").append(declares(activity.getClass(), "onKeyDown"))
                        .append(" keyShortcut=").append(declares(activity.getClass(), "onKeyShortcut"));
            }
        } catch (Throwable ignored) {
        }
        XposedLog.i(sb.toString());
    }

    /** 这个类（含父类）有没有自己声明某个方法。 */
    private static boolean declares(Class<?> clazz, String name) {        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Method method : c.getDeclaredMethods()) {
                    if (method.getName().equals(name)) return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 把球和子 View 自己声明的方法名写进日志。
     *
     * <p>{@code MMFloatingButton} 这个类名没被混淆，方法名很可能也是可读的——
     * 万一手势入口藏在某个 public 方法里，看名字就能找到。
     */
    private static void dumpMethods(View view) {
        logMethods("球类", view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                logMethods("子View[" + i + "]", group.getChildAt(i));
            }
        }
    }

    private static void logMethods(String tag, View view) {
        StringBuilder sb = new StringBuilder(tag).append(' ').append(view.getClass().getName())
                .append(" 声明的方法：");
        try {
            for (Method method : view.getClass().getDeclaredMethods()) {
                sb.append(method.getName()).append('/')
                        .append(method.getParameterTypes().length).append(' ');
            }
        } catch (Throwable ignored) {
        }
        XposedLog.i(sb.toString());
    }

    /** 设置页拖动大小滑杆时调用：把新比例应用到还活着的球上。 */
    static void applyScale() {        synchronized (ATTACHED) {
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
        /** 滑动分几帧走完。 */
        private static final int STEPS = 10;
        /** 每帧间隔，接近真手指 60Hz 的采样间隔。 */
        private static final int MOVE_STEP_MS = 16;
        /** 滑到位后按住多久：官方「瞄一眼」是按住预览、松手即恢复。 */
        private static final long HOLD_MS = 1500L;

        /** 正在给原生补发 CANCEL，这一趟的分发要原样放行。 */
        private boolean injecting;
        /**
         * 正在回放的手势的 downTime。所有球共享一把：注入的事件会经过别的球的
         * 分发链路，必须统一放行，否则模块会把「自己造的手势」当成用户手势再执行一次。
         */
        private static volatile long injectingDownTime;
        /** 回放前的触感开关，回放结束要按原值还回去。 */
        private boolean savedHaptic = true;

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
            // 自己回放的事件：原样交给墨墨，绝不能再走一遍模块的手势判定
            if (injectingDownTime != 0 && event.getDownTime() == injectingDownTime) return null;

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
         * 合成一次「按下 - 滑到位 - 按住不放 - 抬起」的手势交给原生球。
         *
         * <p><b>关键在于「按住」</b>：墨墨的「瞄一眼」不是抬手才触发，而是手指滑到
         * 左滑位置、<b>还没离开屏幕</b>时把例句顶出来，松手立刻恢复原状。
         * 之前把 DOWN → MOVE → UP 在同一个同步循环里一口气发完，整个过程不到一帧，
         * 界面连一次绘制都没走到，日志里就只能是「回放成功、界面无变化」。
         *
         * <p>所以把整个手势拆到多帧：
         * <ol>
         *   <li>DOWN 同步投递，先确定哪一层接住了（球 onTouchEvent → 球分发 → Activity 分发）；</li>
         *   <li>每 16ms 发一个 MOVE，位移和间隔都贴近真手指；</li>
         *   <li>到位后<b>按住 1.5 秒不发 UP</b>，让原生维持「预览例句」的状态；</li>
         *   <li>最后补 UP 收场，界面自行恢复。</li>
         * </ol>
         */
        boolean replay(final int dir) {
            final int w = view.getWidth();
            final int h = view.getHeight();
            if (w <= 0 || h <= 0) return false;

            // 球直径约占控件一半，滑到 46% 明确越过原生激活阈值，又不会把球甩出边界
            final float dist = Math.min(w, h) * 0.46f;
            final float dx;
            final float dy;
            switch (dir) {
                case 1:
                    dx = 0f;
                    dy = -dist;
                    break;
                case 2:
                    dx = 0f;
                    dy = dist;
                    break;
                case 3:
                    dx = -dist;
                    dy = 0f;
                    break;
                case 4:
                    dx = dist;
                    dy = 0f;
                    break;
                default:
                    return false;
            }

            final long downAt = SystemClock.uptimeMillis();
            final Target target = pickTarget(w, h, dx, dy, downAt);
            if (target == null) {
                XposedLog.w("回放：" + dirName(dir) + " 没有任何层级接受 DOWN，放弃");
                return false;
            }

            // 回放的是官方手势，官方可能带振动反馈；模块这边已经去掉振动，临时关掉
            savedHaptic = view.isHapticFeedbackEnabled();
            view.setHapticFeedbackEnabled(false);
            injectingDownTime = downAt;

            XposedLog.i("已按下原生球：方向=" + dir + "（" + dirName(dir) + "），投递="
                    + target.name + "，滑距=" + Math.round(dist) + "，球屏幕位置=("
                    + target.screenX + ',' + target.screenY + ')');

            play(target, dir);

            // 兜底：万一中途页面被销毁，别让 injectingDownTime 永久挂着
            view.postDelayed(this::finishInjection, MOVE_STEP_MS * STEPS + HOLD_MS + 3000L);
            return true;
        }

        /** 依次试三层入口，第一个接住 DOWN 的就是这次注入的目标。 */
        private Target pickTarget(int w, int h, float dx, float dy, long downAt) {
            int[] screen = new int[2];
            try {
                view.getLocationOnScreen(screen);
            } catch (Throwable ignored) {
            }

            // ① 直调球的 onTouchEvent：方法列表证实墨墨在球自己身上声明了 onTouchEvent/1
            Target target = new Target(event -> view.onTouchEvent(event), "球 onTouchEvent",
                    w / 2f, h / 2f, dx, dy, screen, downAt);
            if (target.down()) return target;

            // ② 走一遍球的分发，覆盖手势判定写在 onInterceptTouchEvent 的版本
            target = new Target(event -> view.dispatchTouchEvent(event), "球 dispatchTouchEvent",
                    w / 2f, h / 2f, dx, dy, screen, downAt);
            if (target.down()) return target;

            // ③ 从 Activity 重新分发：事件要窗口坐标，raw 用屏幕坐标
            Activity activity = Util.findActivity(view.getContext());
            if (activity == null) return null;
            int[] win = new int[2];
            try {
                view.getLocationInWindow(win);
            } catch (Throwable ignored) {
                win[0] = screen[0];
                win[1] = screen[1];
            }
            int[] rawOffset = {screen[0] - win[0], screen[1] - win[1]};
            target = new Target(event -> activity.dispatchTouchEvent(event), "Activity 分发",
                    win[0] + w / 2f, win[1] + h / 2f, dx, dy, rawOffset, downAt);
            if (!target.down()) return null;

            // 按住期间禁止 ViewPager / 返回手势层在 MOVE 阶段把事件抢走
            ViewParent parent = view.getParent();
            if (parent != null) {
                try {
                    parent.requestDisallowInterceptTouchEvent(true);
                } catch (Throwable ignored) {
                }
                view.postDelayed(() -> {
                    try {
                        parent.requestDisallowInterceptTouchEvent(false);
                    } catch (Throwable ignored) {
                    }
                }, MOVE_STEP_MS * STEPS + HOLD_MS + 300L);
            }
            return target;
        }

        /** 分帧播放滑动段，到位后按住，最后松手。 */
        private void play(final Target target, final int dir) {
            final Handler handler = new Handler(Looper.getMainLooper());

            for (int i = 1; i <= STEPS; i++) {
                final int step = i;
                handler.postDelayed(() -> {
                    if (!attached()) return;
                    float ratio = step / (float) STEPS;
                    send(target, MotionEvent.ACTION_MOVE,
                            target.x0 + target.dx * ratio, target.y0 + target.dy * ratio,
                            target.downAt + step * MOVE_STEP_MS);
                }, (long) step * MOVE_STEP_MS);
            }

            handler.postDelayed(() -> {
                if (!attached()) {
                    finishInjection();
                    return;
                }
                XposedLog.i("瞄一眼：手指已滑到" + dirName(dir) + "位置并按住不放，等原生预览例句");
                // 按住 400ms 时看一次：这正是官方「瞄一眼」预览例句的窗口
                handler.postDelayed(() -> logPreview(target, "按住期间"), 400L);

                handler.postDelayed(() -> {
                    send(target, MotionEvent.ACTION_UP,
                            target.x0 + target.dx, target.y0 + target.dy,
                            target.downAt + STEPS * MOVE_STEP_MS + HOLD_MS);
                    finishInjection();
                    XposedLog.i("瞄一眼：已松手，界面应自行恢复");
                    handler.postDelayed(() -> logPreview(target, "松手之后"), 400L);
                }, HOLD_MS);
            }, (long) STEPS * MOVE_STEP_MS);
        }

        private boolean attached() {
            try {
                return view.isAttachedToWindow();
            } catch (Throwable t) {
                return true;
            }
        }

        private void finishInjection() {
            if (injectingDownTime == 0) return;
            injectingDownTime = 0;
            try {
                view.setHapticFeedbackEnabled(savedHaptic);
            } catch (Throwable ignored) {
            }
        }

        /** 打一行「界面到底变没变」，这是判断原生有没有真的预览例句的唯一依据。 */
        private void logPreview(Target target, String when) {
            try {
                Activity activity = Util.findActivity(view.getContext());
                if (activity == null) return;
                boolean changed = !target.before.equals(ActionExecutor.visibleTextSignature(activity));
                XposedLog.i("瞄一眼：" + when + "界面" + (changed ? "已变化" : "无变化"));
                if (changed) {
                    XposedLog.i("瞄一眼：" + when + "可见文案=" + ActionExecutor.dumpTexts(activity));
                }
            } catch (Throwable ignored) {
            }
        }

        private void send(Target target, int action, float x, float y, long at) {
            MotionEvent event = obtain(target.downAt, at, action, x, y, target.offset);
            try {
                target.sink.dispatch(event);
            } catch (Throwable ignored) {
            } finally {
                event.recycle();
            }
        }

        /** 一次注入的目标：某一层分发入口 + 它使用的坐标系。 */
        private final class Target {
            final Sink sink;
            final String name;
            final float x0;
            final float y0;
            final float dx;
            final float dy;
            final int[] offset;
            final long downAt;
            final int screenX;
            final int screenY;
            /** 按下之前的可见文案，用来判断「按住期间」到底有没有把例句顶出来。 */
            final String before;

            Target(Sink sink, String name, float x0, float y0, float dx, float dy,
                   int[] offset, long downAt) {
                this.sink = sink;
                this.name = name;
                this.x0 = x0;
                this.y0 = y0;
                this.dx = dx;
                this.dy = dy;
                this.offset = offset;
                this.downAt = downAt;
                this.screenX = offset == null ? Math.round(x0) : offset[0] + Math.round(x0);
                this.screenY = offset == null ? Math.round(y0) : offset[1] + Math.round(y0);
                this.before = signature();
            }

            private String signature() {
                try {
                    Activity activity = Util.findActivity(view.getContext());
                    return activity == null ? "" : ActionExecutor.visibleTextSignature(activity);
                } catch (Throwable t) {
                    return "";
                }
            }

            /**
             * 发 DOWN。没人接就补 CANCEL 收场——否则后续 MOVE/UP 会冒泡到页面，
             * 变成一次「点击屏幕显示答案」。
             */
            boolean down() {
                boolean handled = false;
                MotionEvent event = obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x0, y0, offset);
                try {
                    handled = sink.dispatch(event);
                } catch (Throwable ignored) {
                } finally {
                    event.recycle();
                }
                if (!handled) {
                    MotionEvent cancel = obtain(downAt, downAt + 1,
                            MotionEvent.ACTION_CANCEL, x0, y0, offset);
                    try {
                        sink.dispatch(cancel);
                    } catch (Throwable ignored) {
                    } finally {
                        cancel.recycle();
                    }
                }
                return handled;
            }
        }

        /** 事件投递目标：球控件或 Activity 的分发入口。 */
        private interface Sink {
            boolean dispatch(MotionEvent event);
        }

        /**
         * 造一个「看起来像真手指」的事件。
         *
         * <p>{@code obtain(...)} 里的 x/y 是控件内坐标，raw 默认与之相同；
         * 调 {@code offsetLocation(目标屏幕左上角)} 后 raw 变成屏幕坐标，而 x/y 不变，
         * 和真实触摸事件的结构完全一致。
         */
        private MotionEvent obtain(long downAt, long at, int action,
                                   float x, float y, int[] offset) {
            MotionEvent event = MotionEvent.obtain(downAt, at, action, x, y, 0);
            if (offset != null) event.offsetLocation(offset[0], offset[1]);
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
