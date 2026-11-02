package com.fuoins.momoreviewball;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
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
 * <p>不绘制自己的球：外观、位置、层级全部沿用墨墨的控件，只把触摸事件接管过来，
 * 按模块配置执行动作。自带球原有的手势因此失效，这是接管的前提。
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
            Context context = root.getContext();
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
        final TouchHandler handler;
        synchronized (HOOKED) {
            if (HOOKED.containsKey(view)) return;
            handler = new TouchHandler(view);
            HOOKED.put(view, handler);
        }

        final View.OnTouchListener original = readTouchListener(view);
        view.setOnTouchListener((v, event) -> {
            Config config = Config.get(v.getContext());
            if (!config.enabled) {
                return original != null && original.onTouch(v, event);
            }
            return handler.onTouch(v, event);
        });
        view.post(handler::applyPosition);
        sFound = true;
        XposedLog.i("已接管自带悬浮球，原有 listener=" + original);
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

    /** 自带悬浮球的手势识别。 */
    static final class TouchHandler {

        private final View view;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final int touchSlop;
        private Config config;

        private float downRawX;
        private float downRawY;
        private float baseTranslationX;
        private float baseTranslationY;
        private boolean dragging;
        private boolean longFired;

        private final Runnable longPressRunnable = new Runnable() {
            @Override
            public void run() {
                if (dragging) return;
                longFired = true;
                boolean target = !config.locked;
                config.locked = target;
                config.save(view.getContext());
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                Activity activity = Util.findActivity(view.getContext());
                if (activity != null) {
                    ActionExecutor.toast(activity, target ? "已固定位置，长按可解锁" : "已解锁，可以拖动");
                }
            }
        };

        TouchHandler(View view) {
            this.view = view;
            this.touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
            this.config = Config.get(view.getContext());
        }

        void applyPosition() {
            config = Config.get(view.getContext());
            DisplayMetrics metrics = view.getResources().getDisplayMetrics();
            view.setTranslationX(config.offsetXRatio * metrics.widthPixels);
            view.setTranslationY(config.offsetYRatio * metrics.heightPixels);
        }

        boolean onTouch(View v, MotionEvent event) {
            config = Config.get(v.getContext());
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    baseTranslationX = v.getTranslationX();
                    baseTranslationY = v.getTranslationY();
                    dragging = false;
                    longFired = false;
                    v.setPressed(true);
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
                        DisplayMetrics metrics = v.getResources().getDisplayMetrics();
                        float limitX = metrics.widthPixels * 0.5f;
                        float limitY = metrics.heightPixels * 0.5f;
                        v.setTranslationX(Config.clamp(baseTranslationX + dx, -limitX, limitX));
                        v.setTranslationY(Config.clamp(baseTranslationY + dy, -limitY, limitY));
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnable);
                    v.setPressed(false);
                    if (dragging) commitPosition(v);
                    return true;

                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPressRunnable);
                    v.setPressed(false);
                    if (dragging) {
                        commitPosition(v);
                        return true;
                    }
                    if (longFired) return true;

                    float upDx = event.getRawX() - downRawX;
                    float upDy = event.getRawY() - downRawY;
                    if (Math.hypot(upDx, upDy) < touchSlop * 1.2f) {
                        runAction(v, 0);
                    } else if (Math.abs(upDx) > Math.abs(upDy)) {
                        runAction(v, upDx > 0 ? 4 : 3);
                    } else {
                        runAction(v, upDy > 0 ? 2 : 1);
                    }
                    return true;
            }
            return false;
        }

        private void commitPosition(View v) {
            DisplayMetrics metrics = v.getResources().getDisplayMetrics();
            float tx = v.getTranslationX();
            float ty = v.getTranslationY();
            if (metrics.widthPixels > 0) {
                config.offsetXRatio = tx / metrics.widthPixels;
            }
            if (metrics.heightPixels > 0) {
                config.offsetYRatio = ty / metrics.heightPixels;
            }
            config.save(v.getContext());
        }

        private void runAction(View v, int gestureIndex) {
            Activity activity = Util.findActivity(v.getContext());
            if (activity == null) return;

            boolean answerShown = ActionExecutor.isAnswerShown(activity);
            String action = config.actionFor(answerShown, gestureIndex);
            Log.d(TAG, "gesture=" + Config.GESTURE_LABELS[gestureIndex]
                    + " answerShown=" + answerShown + " action=" + action);
            if (Config.ACTION_NONE.equals(action)) return;

            boolean ok = ActionExecutor.execute(activity, action, v);
            if (ok && config.haptic) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            } else if (!ok) {
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
