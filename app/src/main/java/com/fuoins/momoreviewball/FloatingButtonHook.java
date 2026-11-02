package com.fuoins.momoreviewball;

import android.app.Activity;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import org.xmlpull.v1.XmlPullParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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

    private static volatile boolean sClassHooked;
    private static volatile boolean sInflateHooked;
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
        }
        view.setOnTouchListener(handler);

        // 球面标识：沿轮廓的四向色带 + 中心动作文字，纯叠加，不拦截触摸
        try {
            XposedLog.i("准备挂载球面标识：host=" + view.getClass().getSimpleName()
                    + " 尺寸=" + view.getWidth() + "x" + view.getHeight()
                    + " 父=" + (view.getParent() == null
                            ? "null" : view.getParent().getClass().getSimpleName())
                    + " overlay=" + view.getOverlay());
            view.getOverlay().add(overlay);
            XposedLog.i("球面标识已加入 overlay");
            overlay.start();

            // ViewOverlay 只在 add 的那一刻按控件宽高设一次 bounds。
            // 构造 / inflate 阶段控件还没测量，尺寸为 0，bounds 会一直是 0×0，
            // 之后 draw 就再也画不出东西——必须在布局完成后补一次，并跟着尺寸变化。
            view.addOnLayoutChangeListener((v, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> {
                overlay.setBounds(0, 0, v.getWidth(), v.getHeight());
            });
            view.post(() -> {
                overlay.setBounds(0, 0, view.getWidth(), view.getHeight());
                XposedLog.i("已设置球面标识范围 " + view.getWidth() + "x" + view.getHeight());
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
            XposedLog.w("叠加球面标识失败", t);
        }

        sFound = true;
        XposedLog.i("已代理自带悬浮球，原有 listener=" + original);

        // 球体几何诊断：控件本身不是正方形，真实球的区域靠这段日志确认
        view.post(() -> XposedLog.i("球控件尺寸 " + view.getWidth() + "x" + view.getHeight()
                + "，子View=" + (view instanceof ViewGroup
                        ? ((ViewGroup) view).getChildCount() : -1)
                + "，背景=" + view.getBackground()));

        // 复习页一出现就把日志落盘，设置页注入失败时也能从文件里拿到
        view.postDelayed(() -> Diag.flush(view.getContext()), 1500L);
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
                    // 用自己记的最后一个 MOVE 坐标，不依赖抬手事件本身
                    float dx = lastRawX - downX;
                    float dy = lastRawY - downY;
                    reset();
                    overlay.setHighlight(0, 0f);

                    if (wasDragging) {
                        // 拖动结束，抬手交给原生做边缘吸附
                        return forward(v, event);
                    }

                    // 拦下这次抬手：先给原生补一个 CANCEL 清理按压态，原生动作就不会触发
                    if (!config.locked) cancelNative(v, event);
                    if (Math.hypot(dx, dy) <= slop * 1.5f) {
                        runAction(v, 0, config);
                    } else {
                        runAction(v, direction(dx, dy), config);
                    }
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    reset();
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
            XposedLog.d("gesture=" + Config.GESTURE_LABELS[gestureIndex]
                    + " 已显示答案=" + answerShown + " 动作=" + action);
            if (Config.ACTION_NONE.equals(action)) return;

            boolean ok = ActionExecutor.execute(activity, action, v);
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
