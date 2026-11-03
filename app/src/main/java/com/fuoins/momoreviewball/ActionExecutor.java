package com.fuoins.momoreviewball;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/**
 * 动作执行器。
 *
 * 墨墨 5.5.25 使用了加固，内部方法无法静态定位；这里改为在复习页的视图树里
 * 查找对应的按钮/文案并触发点击，不依赖加固后的类名，升级小版本也能用。
 */
public final class ActionExecutor {

    private static final String TAG = "MoMoBall";

    private ActionExecutor() {
    }

    public static boolean execute(Activity activity, String action, View anchor) {
        if (activity == null || action == null) return false;
        boolean ok;
        switch (action) {
            case Config.ACTION_KNOW:
                ok = clickId(activity, "rev_familiar")
                        || clickText(activity, new String[]{"认识"}, true);
                break;
            case Config.ACTION_VAGUE:
                ok = clickId(activity, "rev_vague")
                        || clickText(activity, new String[]{"模糊"}, true);
                break;
            case Config.ACTION_FORGET:
                ok = clickId(activity, "rev_forget")
                        || clickText(activity, new String[]{"忘记"}, true);
                break;
            case Config.ACTION_FAMILIAR:
                ok = markFamiliar(activity);
                break;
            case Config.ACTION_PRONOUNCE:
                ok = pronounce(activity);
                break;
            case Config.ACTION_SHOW:
                ok = showAnswer(activity);
                break;
            case Config.ACTION_PEEK:
                ok = peek(activity);
                break;
            case Config.ACTION_PREV:
                ok = previous(activity);
                break;
            default:
                ok = false;
                break;
        }
        if (!ok) {
            Log.w(TAG, "action not found: " + action + ", visible texts: " + dumpTexts(activity));
        }
        return ok;
    }

    /** 上一次「熟知」手势的时间；手势执行中不接第二次，避免翻页瞬间连点两下。 */
    private static long familiarGestureAt;
    private static boolean familiarGestureRunning;

    private static final String TAG_BOTTOM_FAMILIAR = "momo_ball_familiar";

    /** 复习页的 ReviewFragment；由 ModuleEntry hook onViewCreated 捕获。 */
    private static volatile Object reviewFragment;

    public static void setReviewFragment(Object fragment) {
        if (fragment != null) reviewFragment = fragment;
    }

    /**
     * 给底栏补一个「熟知」按钮，把「认识 / 模糊 / 忘记」三个变成四个。
     *
     * <p>底栏是 ConstraintLayout，三个按钮组成的是一条水平链（0dp 宽、互相约束）。
     * 直接 addView 只会得到 0 宽，所以要把链拆开：认识.right 指到新按钮，
     * 新按钮再指到模糊.left。字段名被 R8 混淆，这里按「值等于邻居 id」反查。
     *
     * <p>墨墨翻页会重建操作栏，由 {@link BallOverlay} 每 400ms 复查一次。
     */
    public static void ensureBottomFamiliar(Activity activity) {
        if (activity == null) return;
        Config config = Config.get(activity);
        View barView = findByIdName(activity, "rev_operation_bar");
        View existing = barView instanceof ViewGroup
                ? findChildByTag((ViewGroup) barView, TAG_BOTTOM_FAMILIAR) : null;

        if (!config.autoFamiliar) {
            if (existing != null && existing.getParent() instanceof ViewGroup) {
                ((ViewGroup) existing.getParent()).removeView(existing);
                XposedLog.i("熟知：设置已关闭，移除底栏「熟知」按钮");
            }
            return;
        }
        if (!(barView instanceof ViewGroup)) return;
        ViewGroup bar = (ViewGroup) barView;
        if (existing != null) return;

        View famView = findByIdName(activity, "rev_familiar");
        View vagueView = findByIdName(activity, "rev_vague");
        if (!(famView instanceof TextView) || vagueView == null) return;
        if (famView.getParent() != bar || vagueView.getParent() != bar) return;
        if (famView.getWidth() <= 0 || famView.getHeight() <= 0) return;

        TextView src = (TextView) famView;
        boolean ok;
        if (bar instanceof LinearLayout) {
            ok = injectLinear(activity, bar, src);
        } else {
            ok = injectConstraint(activity, bar, src, vagueView);
        }
        if (!ok) {
            XposedLog.w("熟知：底栏注入失败，操作栏=" + bar.getClass().getName()
                    + " lp=" + (src.getLayoutParams() == null ? "null"
                    : src.getLayoutParams().getClass().getName()));
        }
    }

    /** ConstraintLayout 版：拆链、插按钮、重新接链。 */
    private static boolean injectConstraint(Activity activity, ViewGroup bar,
                                            TextView src, View vague) {
        ViewGroup.LayoutParams srcLp = src.getLayoutParams();
        ViewGroup.LayoutParams vagueLp = vague.getLayoutParams();
        if (srcLp == null || vagueLp == null) return false;
        Class<?> lpClass = srcLp.getClass();
        int famId = src.getId();
        int vagueId = vague.getId();
        if (famId == View.NO_ID || vagueId == View.NO_ID) return false;

        // 认识.right -> 模糊.left 的那个字段；模糊.left -> 认识.right 的那个字段
        Field rightField = findIntField(lpClass, srcLp, vagueId);
        Field leftField = findIntField(lpClass, vagueLp, famId);
        if (rightField == null || leftField == null) {
            XposedLog.w("熟知：约束字段没找到 right=" + rightField + " left=" + leftField
                    + " lp=" + lpClass.getName());
            return false;
        }

        TextView button = buildButton(activity, src);
        int newId = View.generateViewId();
        button.setId(newId);

        try {
            Object ourLp = copyLp(lpClass, vagueLp, 0, vagueLp.height);
            rightField.setInt(ourLp, vagueId);   // our.right -> 模糊.left
            leftField.setInt(ourLp, famId);      // our.left  -> 认识.right

            rightField.setInt(srcLp, newId);     // 认识.right -> our.left
            src.setLayoutParams(srcLp);
            leftField.setInt(vagueLp, newId);    // 模糊.left  -> our.right
            vague.setLayoutParams(vagueLp);

            bar.addView(button, (ViewGroup.LayoutParams) ourLp);
        } catch (Throwable t) {
            XposedLog.w("熟知：约束改写失败 " + t);
            return false;
        }
        bar.requestLayout();
        XposedLog.i("熟知：底栏已补上第四个按钮（ConstraintLayout），字段="
                + leftField.getName() + "/" + rightField.getName()
                + " 操作栏宽=" + bar.getWidth());
        logButtonLater(button, bar);
        return true;
    }

    /** 线性布局兜底：按权重均分。 */
    private static boolean injectLinear(Activity activity, ViewGroup bar, TextView src) {
        ViewGroup.LayoutParams srcLp = src.getLayoutParams();
        if (srcLp == null) return false;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, srcLp.height, 1f);
        if (srcLp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams from = (LinearLayout.LayoutParams) srcLp;
            lp.gravity = from.gravity;
            lp.leftMargin = from.leftMargin;
            lp.rightMargin = from.rightMargin;
            lp.topMargin = from.topMargin;
            lp.bottomMargin = from.bottomMargin;
        }
        TextView button = buildButton(activity, src);
        bar.addView(button, 0, lp);
        bar.requestLayout();
        XposedLog.i("熟知：底栏已补上第四个按钮（LinearLayout） 操作栏宽=" + bar.getWidth());
        logButtonLater(button, bar);
        return true;
    }

    private static TextView buildButton(Activity activity, TextView src) {
        Config config = Config.get(activity);
        int color = config.colorOf(Config.ACTION_FAMILIAR);

        TextView button = new TextView(activity);
        button.setText("熟知");
        button.setTag(TAG_BOTTOM_FAMILIAR);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.getTextSize());
        button.setTypeface(src.getTypeface());
        button.setGravity(src.getGravity());
        button.setIncludeFontPadding(true);
        button.setPadding(src.getPaddingLeft(), src.getPaddingTop(),
                src.getPaddingRight(), src.getPaddingBottom());
        button.setTextColor(color);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setClickable(true);
        button.setFocusable(true);
        styleButton(button, src, color, activity.getResources());
        button.setOnClickListener(v -> {
            Activity owner = Util.findActivity(v.getContext());
            if (owner != null) markFamiliar(owner);
        });
        return button;
    }

    /** 克隆「认识」的圆角描边背景并染成熟知色，再补一个按压态。 */
    private static void styleButton(TextView button, TextView src, int color,
                                    android.content.res.Resources res) {
        float density = res.getDisplayMetrics().density;
        int radius = (int) (10f * density + 0.5f);
        int stroke = Math.max(1, (int) (2f * density + 0.5f));
        try {
            Drawable bg = src.getBackground();
            if (bg != null && bg.getConstantState() != null) {
                Drawable copy = bg.getConstantState().newDrawable(res).mutate();
                copy.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                button.setBackground(copy);
            } else {
                button.setBackground(roundStroke(radius, stroke, color, 0));
            }
        } catch (Throwable t) {
            button.setBackground(roundStroke(radius, stroke, color, 0));
        }
        try {
            StateListDrawable fg = new StateListDrawable();
            fg.addState(new int[]{android.R.attr.state_pressed},
                    roundStroke(radius, stroke, color, (color & 0x00FFFFFF) | 0x1A000000));
            fg.addState(new int[0], new ColorDrawable(0));
            button.setForeground(fg);
        } catch (Throwable ignored) {
        }
    }

    private static GradientDrawable roundStroke(int radius, int stroke, int color, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
        g.setStroke(stroke, color);
        g.setColor(fill);
        return g;
    }

    private static final String[] LP_SKIP_FIELDS = {
            "width", "height", "leftMargin", "rightMargin", "topMargin", "bottomMargin",
            "marginStart", "marginEnd", "gravity", "horizontalWeight"
    };

    /** 在 LayoutParams 的 public int 字段里找值等于 value 的那个（即指向 value 的约束）。 */
    private static Field findIntField(Class<?> cls, Object lp, int value) {
        Field found = null;
        String foundName = null;
        for (Field f : cls.getFields()) {
            if (f.getType() != int.class) continue;
            String name = f.getName();
            boolean skip = false;
            for (String s : LP_SKIP_FIELDS) {
                if (s.equals(name)) {
                    skip = true;
                    break;
                }
            }
            if (skip) continue;
            try {
                if (f.getInt(lp) != value) continue;
            } catch (Throwable ignored) {
                continue;
            }
            if (found == null) {
                found = f;
                foundName = name;
            } else {
                XposedLog.d("熟知：约束字段有歧义 " + value + ": " + foundName + " / " + name);
            }
        }
        return found;
    }

    private static Object copyLp(Class<?> cls, Object src, int width, int height) throws Throwable {
        Constructor<?> ctor = cls.getConstructor(int.class, int.class);
        Object dst = ctor.newInstance(width, height);
        for (Field f : cls.getFields()) {
            if (f.getType() != int.class) continue;
            try {
                f.setInt(dst, f.getInt(src));
            } catch (Throwable ignored) {
            }
        }
        ViewGroup.LayoutParams d = (ViewGroup.LayoutParams) dst;
        d.width = width;
        d.height = height;
        return dst;
    }

    private static void logButtonLater(View button, ViewGroup bar) {
        button.postDelayed(() -> {
            if (button.isAttachedToWindow()) {
                XposedLog.i("熟知：底栏按钮 300ms 后 " + describe(button)
                        + " 操作栏宽=" + bar.getWidth());
            }
        }, 300L);
    }

    private static View findChildByTag(ViewGroup parent, Object tag) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (tag.equals(child.getTag())) return child;
        }
        return null;
    }

    private static boolean isReallyVisible(View view) {
        return view != null && view.isShown() && view.getAlpha() > 0.99f
                && view.getWidth() > 0 && view.getHeight() > 0;
    }

    /**
     * 直接调用墨墨自己的应答链路：{@code ReviewFragment.X0(WELL_FAMILIAR.value, false)}。
     *
     * <p>和顶栏「标识熟知」图标、长按认识上划是同一个入口，不会漏掉弹窗/埋点/统计。
     * 方法名是 R8 混淆后的，换版本可能变；失败就返回 false，由调用方退回手势。
     */
    private static boolean invokeWellFamiliar() {
        Object fragment = reviewFragment;
        if (fragment == null) return false;
        try {
            int value = 4;
            try {
                Class<?> rr = Class.forName("com.maimemo.android.momo.revision.ReviewResponse",
                        false, fragment.getClass().getClassLoader());
                Object[] constants = rr.getEnumConstants();
                if (constants != null) {
                    for (Object c : constants) {
                        if ("WELL_FAMILIAR".equals(((Enum<?>) c).name())) {
                            value = rr.getField("value").getInt(c);
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                XposedLog.d("熟知：读 ReviewResponse 失败，用 fallback " + value + "：" + t);
            }

            Method x0 = findMethod(fragment.getClass(), "X0", int.class, boolean.class);
            if (x0 != null) {
                x0.setAccessible(true);
                x0.invoke(fragment, value, false);
                XposedLog.i("熟知：已调用 ReviewFragment.X0(" + value + ", false)");
                return true;
            }
            Method g0 = findStaticWithInstance(fragment.getClass(), "G0", fragment);
            if (g0 != null) {
                g0.setAccessible(true);
                g0.invoke(null, fragment, true, true);
                XposedLog.i("熟知：已调用 ReviewFragment.G0(fragment, true, true)");
                return true;
            }
            XposedLog.w("熟知：ReviewFragment 里没有 X0/G0，退回手势");
        } catch (Throwable t) {
            XposedLog.w("熟知：调用原生标记失败 " + t);
        }
        return false;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findStaticWithInstance(Class<?> cls, String name, Object instance) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 3 && ps[0].isInstance(instance)
                        && ps[1] == boolean.class && ps[2] == boolean.class) {
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * 标记「熟知」。
     *
     * <p>墨墨底栏只有「认识 / 模糊 / 忘记」，「熟知」藏在「认识」的长按手势里：
     * 按住「认识」不动，等浮层出来，再往上滑到「标记熟知」松手。这里复现整套手势，
     * 而不是去翻顶部工具栏那个被墨墨按设置藏起来的图标。
     *
     * <p>答案没出现时底栏还没有「认识」按钮，这时退回顶部入口（如果墨墨自己露出来了）。
     */
    public static boolean markFamiliar(Activity activity) {
        if (activity == null) return false;
        Config config = Config.get(activity);
        // 顶栏入口如果被墨墨放出来了，直接点它；没有就走底栏「认识」的长按上划。
        // 两个入口开哪个都能用，这里是「谁在就点谁」。
        View top = findByIdName(activity, "iv_well_familiar");
        if (isReallyVisible(top) && top.performClick()) {
            XposedLog.i("熟知：点了顶栏入口 " + describe(top));
            return true;
        }
        if (!config.autoFamiliar) return false;
        // 底栏「熟知」按钮/悬浮球共用：优先走墨墨自己的应答链路，
        // 版本对不上（X0 改名）再退回长按上划的手势。
        if (invokeWellFamiliar()) return true;
        View know = findByIdName(activity, "rev_familiar");
        if (know == null || !know.isShown() || know.getWidth() <= 0 || know.getHeight() <= 0) {
            return clickId(activity, "iv_well_familiar");
        }
        long now = SystemClock.uptimeMillis();
        if (familiarGestureRunning || now - familiarGestureAt < 1200L) return true;
        familiarGestureAt = now;
        startFamiliarGesture(activity, know);
        return true;
    }

    /**
     * 按住「认识」→ 等长按浮层 → 上划 → 松手。
     *
     * <p>整个序列必须由模块自己发 MotionEvent：墨墨的「上划标记熟知」是按钮自己的
     * 触摸逻辑，performClick / performLongClick 都不会走那条分支。
     */
    private static void startFamiliarGesture(final Activity activity, final View button) {
        final View decor;
        try {
            decor = activity.getWindow().getDecorView();
        } catch (Throwable t) {
            return;
        }
        if (decor == null) return;

        int[] buttonPos = new int[2];
        int[] decorPos = new int[2];
        button.getLocationOnScreen(buttonPos);
        decor.getLocationOnScreen(decorPos);
        final float startX = buttonPos[0] - decorPos[0] + button.getWidth() / 2f;
        final float startY = buttonPos[1] - decorPos[1] + button.getHeight() / 2f;

        // 浮层贴在「认识」上方，滑过按钮上沿再往上 120dp 足够触发；
        // 只滑一点点墨墨会当成「松手取消」。
        float density = activity.getResources().getDisplayMetrics().density;
        float distance = Math.max(120f * density, button.getHeight() * 1.2f);
        final float endY = Math.max(decor.getHeight() * 0.2f, startY - distance);

        final long downTime = SystemClock.uptimeMillis();
        final Handler handler = new Handler(Looper.getMainLooper());
        familiarGestureRunning = true;

        sendTouch(decor, downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY);
        XposedLog.i("熟知：长按底栏「认识」 " + describe(button)
                + "，随后上划 " + (int) (startY - endY) + "px");

        // 500ms 才算长按，留 140ms 余量等墨墨把提示浮层画出来
        handler.postDelayed(() -> {
            final int steps = 10;
            for (int i = 1; i <= steps; i++) {
                final int step = i;
                handler.postDelayed(() -> {
                    float y = startY + (endY - startY) * step / steps;
                    sendTouch(decor, downTime, SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_MOVE, startX, y);
                }, step * 26L);
            }
            handler.postDelayed(() -> {
                sendTouch(decor, downTime, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP, startX, endY);
                familiarGestureRunning = false;
                XposedLog.i("熟知：已上划到「标记熟知」并松手");
            }, steps * 26L + 80L);
        }, 640L);
    }

    private static void sendTouch(View target, long downTime, long eventTime,
                                  int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        try {
            target.dispatchTouchEvent(event);
        } catch (Throwable t) {
            XposedLog.w("熟知：手势发送失败 " + t);
        } finally {
            event.recycle();
        }
    }

    private static String visibilityName(int visibility) {
        switch (visibility) {
            case View.VISIBLE:
                return "V";
            case View.INVISIBLE:
                return "I";
            case View.GONE:
                return "G";
            default:
                return String.valueOf(visibility);
        }
    }

    private static String describe(View view) {
        return view.getClass().getSimpleName() + "#" + idName(view)
                + " " + view.getWidth() + "x" + view.getHeight()
                + " @(" + view.getLeft() + "," + view.getTop() + ")"
                + " alpha=" + view.getAlpha()
                + " " + visibilityName(view.getVisibility());
    }

    private static String parentChain(View view) {
        StringBuilder builder = new StringBuilder();
        View cur = view;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            builder.append(cur.getClass().getSimpleName()).append('#').append(idName(cur))
                    .append('=').append(visibilityName(cur.getVisibility())).append(" < ");
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return builder.toString();
    }

    private static String idName(View view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable t) {
            return "";
        }
    }

    /** 当前是否已经显示答案：复习页出现「认识/忘记」评分按钮即视为已显示。 */
    public static boolean isAnswerShown(Activity activity) {
        if (activity == null) return false;

        View bar = findByIdName(activity, "rev_cl_operation_bar");
        if (bar != null) {
            if (!bar.isShown()) return false;
            // 评分卡片在部分版本里整页常驻，必须内部按钮也可见才算答案已显示。
            View know = findByIdName(activity, "rev_familiar");
            View forget = findByIdName(activity, "rev_forget");
            if (know != null && know.isShown()) return true;
            if (forget != null && forget.isShown()) return true;
            if (know == null && forget == null) return true;
            return false;
        }

        // 没有操作栏这个 id 时，必须确认页面上真有评分按钮才继续用文字判断。
        // 设置页、列表页里「认识 / 忘记」到处都是，直接看文字会把设置页当成复习页。
        if (findByIdName(activity, "rev_familiar") == null
                && findByIdName(activity, "rev_forget") == null) {
            return false;
        }
        View root = activity.getWindow().getDecorView();
        boolean know = hasVisibleText(root, "认识");
        boolean forget = hasVisibleText(root, "忘记");
        return know && forget;
    }

    private static boolean pronounce(Activity activity) {
        if (clickId(activity, "rev_play_spelling")) return true;
        if (clickByDescription(activity, new String[]{"播放单词发音", "单词发音", "发音", "朗读", "喇叭"})) {
            return true;
        }
        return clickByIdKeyword(activity, new String[]{
                "voice", "sound", "speak", "pronounce", "audio", "play", "speaker", "loud"
        });
    }

    private static boolean showAnswer(Activity activity) {
        if (isAnswerShown(activity)) return false;
        if (clickId(activity, "cl_content_hint")) return true;
        if (clickText(activity, new String[]{"显示答案", "显示单词", "查看答案"}, true)) {
            return true;
        }
        if (clickTextContains(activity, "点击屏幕显示答案")) {
            return true;
        }
        View decor = activity.getWindow().getDecorView();
        if (decor.getWidth() > 0 && decor.getHeight() > 0) {
            dispatchTap(decor, decor.getWidth() / 2f, decor.getHeight() * 0.45f);
            return true;
        }
        return false;
    }

    /**
     * 官方「瞄一眼」。
     *
     * <p>墨墨的「瞄一眼」是复习页自带的功能：答案还没出现时，用它看一眼例句提示。
     * 官方给的入口只有两个，都不是可见按钮：
     * <ul>
     *   <li>复习页悬浮球在「出现答案前」的<b>左滑</b>（资源里的说明原文：
     *       「发音，瞄一眼、返回上一个单词等操作」）；</li>
     *   <li>键盘快捷键 <b>Ctrl+O</b>（快捷键帮助原文：「Ctrl+O => 瞄一眼」）。</li>
     * </ul>
     * 所以这里把「左滑」原样回放给原生球，由墨墨自己的手势识别执行，
     * 完全不复制它的业务逻辑，也不自绘任何提示层。
     */
    private static boolean peek(Activity activity) {
        // ① 页面上真有官方「瞄一眼」控件就点它
        if (clickPeekControl(activity)) return true;

        // 答案已经出来了，瞄一眼没有意义
        if (isAnswerShown(activity)) return sendPeekShortcut(activity);

        // ② 主路径：官方手势回调。走到这里说明抬手前没能提前触发
        //    （比如滑动采样太少），补一段 1.6 秒的临时预览再自动收起。
        if (beginPeekPreview(activity)) {
            final View decor = activity.getWindow().getDecorView();
            decor.postDelayed(() -> {
                if (!peekPressing) return;
                endPeekPreview();
                XposedLog.i("瞄一眼：临时预览时间到，已收手");
            }, PEEK_HOLD_MS);
            return true;
        }

        return sendPeekShortcut(activity);
    }

    /** 例句预览是否正由模块通过官方回调维持着。 */
    private static boolean peekPressing;

    /**
     * 开始「瞄一眼」预览：把官方左滑手势码交给墨墨自己的回调。
     *
     * <p>这一步发生在<b>手指还没离开屏幕</b>时，所以例句会在按住期间显示；
     * 抬手时由 {@link #endPeekPreview()} 补 a(-1)，官方自己收回。
     * 不合成触摸、不自绘提示层，页面状态完全由墨墨维护。
     */
    static boolean beginPeekPreview(Context context) {
        Activity activity = Util.findActivity(context);
        if (activity == null) return false;
        // 答案已经显示时「瞄一眼」没有意义，也不能把评分栏顶掉
        if (isAnswerShown(activity)) return false;
        if (peekPressing) endPeekPreview();

        if (!FloatingButtonHook.callFloatBtnListener(GESTURE_LEFT_CODE)) {
            XposedLog.w("瞄一眼：官方手势回调不可用");
            return false;
        }
        peekPressing = true;
        XposedLog.i("瞄一眼：滑到位即调官方左滑 a(" + GESTURE_LEFT_CODE + ")，按住期间应显示例句");

        final String before = visibleTextSignature(activity);
        activity.getWindow().getDecorView().postDelayed(() -> {
            if (!peekPressing) return;
            try {
                if (peekPreviewShown(activity, before)) {
                    XposedLog.i("瞄一眼：例句已显示（按住中）");
                } else {
                    XposedLog.w("瞄一眼：a(" + GESTURE_LEFT_CODE + ") 后界面无变化，可见文案="
                            + dumpTexts(activity));
                }
            } catch (Throwable ignored) {
            }
        }, 450L);
        return true;
    }

    /** 松手：补官方收手码，让墨墨把例句收回去。 */
    static void endPeekPreview() {
        if (!peekPressing) return;
        peekPressing = false;
        FloatingButtonHook.callFloatBtnListener(GESTURE_RELEASE_CODE);
        XposedLog.i("瞄一眼：松手补 a(" + GESTURE_RELEASE_CODE + ")，例句收起");
    }

    /**
     * 手动左滑时官方回调收到的方向码。
     *
     * <p>3.1.6 日志里，手指按住球拖动的整个过程每 8ms 回调一次 {@code a(-1)}，
     * 收手那一刻回调一次 {@code a(20)}，随后又回到 {@code a(-1)}。
     * 所以 20 就是「左滑」这个手势的码，-1 表示「还在拖动、方向未定」。
     */
    private static final int GESTURE_LEFT_CODE = 20;

    /** 官方松手时回调的码：结束「瞄一眼」预览。 */
    private static final int GESTURE_RELEASE_CODE = -1;

    /** 直调回调时，例句保持多久再自动收手。 */
    private static final long PEEK_HOLD_MS = 1600L;

    /**
     * 按住预览成功的判据：界面多了文案，而且<b>答案没有被点出来</b>。
     *
     * <p>「瞄一眼」只会在单词上方多出一条例句，评分栏不会出现。一旦评分栏出现，
     * 说明这次注入被页面当成了一次点击，必须立刻判定失败，不能当成功糊过去。
     */
    private static boolean peekPreviewShown(Activity activity, String before) {
        if (!peekChanged(activity, before)) return false;
        XposedLog.i("瞄一眼：界面变化后的可见文案=" + dumpTexts(activity));
        if (isAnswerShown(activity)) {
            XposedLog.w("瞄一眼：页面把预览当成了点击、答案被顶出来了，判为失败");
            return false;
        }
        return true;
    }

    private static boolean peekChanged(Activity activity, String before) {
        try {
            return !before.equals(visibleTextSignature(activity));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前可见文案的指纹，用来判断回放后界面到底有没有变化。 */
    static String visibleTextSignature(Activity activity) {
        ArrayList<TextView> views = new ArrayList<>();
        collectTextViews(activity.getWindow().getDecorView(), views);
        StringBuilder builder = new StringBuilder();
        for (TextView view : views) {
            if (!view.isShown()) continue;
            String text = normalize(view.getText());
            if (text.length() == 0) continue;
            builder.append(text).append('\n');
        }
        return builder.toString();
    }

    /** 页面上如果直接有「瞄一眼」控件（文字或 contentDescription），优先点它。 */
    private static boolean clickPeekControl(Activity activity) {
        ArrayList<View> views = new ArrayList<>();
        collectViews(activity.getWindow().getDecorView(), views);

        for (View view : views) {
            if (!view.isShown()) continue;
            CharSequence text = view instanceof TextView ? ((TextView) view).getText() : null;
            CharSequence desc = view.getContentDescription();
            if (containsPeek(text) || containsPeek(desc)) {
                if (clickView(activity, view)) {
                    XposedLog.i("瞄一眼：点击页面控件 " + view.getClass().getSimpleName());
                    return true;
                }
            }
        }
        for (View view : views) {
            if (!view.isShown()) continue;
            int id = view.getId();
            if (id == View.NO_ID) continue;
            String name;
            try {
                name = activity.getResources().getResourceEntryName(id);
            } catch (Throwable t) {
                continue;
            }
            if (name == null) continue;
            name = name.toLowerCase(Locale.ROOT);
            if (name.contains("peek") || name.contains("glance")) {
                if (clickView(activity, view)) {
                    XposedLog.i("瞄一眼：点击 " + name);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsPeek(CharSequence text) {
        return text != null && text.toString().contains("瞄一眼");
    }

    /**
     * 官方快捷键 Ctrl+O = 瞄一眼（快捷键帮助里写死的）。
     *
     * <p>直接交给 Activity 的事件分发，应用自己的 onKeyDown / dispatchKeyEvent
     * 会执行和球上手势完全相同的逻辑。合成的 KeyEvent 必须标成键盘来源，
     * 否则部分实现会按 source 过滤掉；分发改不动时再直接调 onKeyDown 兜底。
     */
    private static boolean sendPeekShortcut(Activity activity) {
        try {
            boolean consumed = activity.dispatchKeyEvent(peekKey(KeyEvent.ACTION_DOWN, 0));
            consumed |= activity.dispatchKeyEvent(peekKey(KeyEvent.ACTION_UP, 40));
            if (consumed) {
                XposedLog.i("瞄一眼：Ctrl+O 已被应用消费");
                return true;
            }
            // 分发链被某个输入框吃掉时（返回 true 却没动作）这条也未必有效，
            // 但至少能覆盖「Window 没把按键交给 Activity」的版本
            boolean direct = callKeyDown(activity);
            XposedLog.i("瞄一眼：Ctrl+O 分发未消费，直调 onKeyDown=" + direct);
            return direct;
        } catch (Throwable t) {
            XposedLog.w("瞄一眼：Ctrl+O 发送失败", t);
            return false;
        }
    }

    private static KeyEvent peekKey(int action, long delay) {
        long now = SystemClock.uptimeMillis();
        int meta = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        KeyEvent event = new KeyEvent(now, now + delay, action, KeyEvent.KEYCODE_O, 0, meta);
        try {
            event.setSource(InputDevice.SOURCE_KEYBOARD);
        } catch (Throwable ignored) {
        }
        return event;
    }

    /** 反射直调 Activity.onKeyDown（会虚分派到墨墨自己的重写版本）。 */
    private static boolean callKeyDown(Activity activity) {
        try {
            java.lang.reflect.Method method =
                    Activity.class.getDeclaredMethod("onKeyDown", int.class, KeyEvent.class);
            method.setAccessible(true);
            Object result = method.invoke(activity, KeyEvent.KEYCODE_O, peekKey(KeyEvent.ACTION_DOWN, 0));
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean previous(Activity activity) {
        if (clickId(activity, "tv_back2")) return true;
        if (clickText(activity, new String[]{"返回上一个单词", "上一个单词", "上一个"}, true)) {
            return true;
        }
        if (clickByDescription(activity, new String[]{"返回上一个单词", "上一个"})) {
            return true;
        }
        return clickByIdKeyword(activity, new String[]{"prev", "undo"});
    }

    private static View findByIdName(Activity activity, String name) {
        try {
            int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
            if (id != 0) return activity.findViewById(id);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean clickId(Activity activity, String... names) {
        for (String name : names) {
            View view = findByIdName(activity, name);
            if (view != null && view.isShown() && view.isEnabled()) {
                if (clickView(activity, view)) return true;
            }
        }
        return false;
    }

    private static boolean clickText(Activity activity, String[] labels, boolean shownOnly) {
        ArrayList<TextView> views = new ArrayList<>();
        collectTextViews(activity.getWindow().getDecorView(), views);

        for (String label : labels) {
            for (TextView view : views) {
                if (shownOnly && !view.isShown()) continue;
                if (normalize(view.getText()).equals(label)) {
                    return clickView(activity, view);
                }
            }
        }
        for (String label : labels) {
            for (TextView view : views) {
                if (shownOnly && !view.isShown()) continue;
                String text = normalize(view.getText());
                if (text.startsWith(label) && text.length() <= label.length() + 3) {
                    return clickView(activity, view);
                }
            }
        }
        return false;
    }

    private static boolean clickTextContains(Activity activity, String keyword) {
        ArrayList<TextView> views = new ArrayList<>();
        collectTextViews(activity.getWindow().getDecorView(), views);
        for (TextView view : views) {
            if (!view.isShown()) continue;
            if (normalize(view.getText()).contains(keyword)) {
                return clickView(activity, view);
            }
        }
        return false;
    }

    private static boolean clickByDescription(Activity activity, String[] keywords) {
        ArrayList<View> views = new ArrayList<>();
        collectViews(activity.getWindow().getDecorView(), views);
        for (View view : views) {
            if (!view.isShown()) continue;
            CharSequence desc = view.getContentDescription();
            if (desc == null) continue;
            String text = desc.toString();
            for (String keyword : keywords) {
                if (text.contains(keyword)) {
                    return clickView(activity, view);
                }
            }
        }
        return false;
    }

    private static boolean clickByIdKeyword(Activity activity, String[] keywords) {
        ArrayList<View> views = new ArrayList<>();
        collectViews(activity.getWindow().getDecorView(), views);
        for (View view : views) {
            if (!view.isShown()) continue;
            int id = view.getId();
            if (id == View.NO_ID) continue;
            String name;
            try {
                name = activity.getResources().getResourceEntryName(id);
            } catch (Throwable t) {
                continue;
            }
            if (name == null) continue;
            name = name.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (name.contains(keyword)) {
                    return clickView(activity, view);
                }
            }
        }
        return false;
    }

    private static boolean clickView(Activity activity, View target) {
        View current = target;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isShown() && current.isEnabled() && current.isClickable()) {
                if (current.performClick()) return true;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        if (target.isShown() && target.getWidth() > 0 && target.getHeight() > 0) {
            View decor = activity.getWindow().getDecorView();
            int[] targetPos = new int[2];
            int[] decorPos = new int[2];
            target.getLocationOnScreen(targetPos);
            decor.getLocationOnScreen(decorPos);
            float x = targetPos[0] - decorPos[0] + target.getWidth() / 2f;
            float y = targetPos[1] - decorPos[1] + target.getHeight() / 2f;
            dispatchTap(decor, x, y);
            return true;
        }
        return false;
    }

    private static void dispatchTap(View root, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        try {
            root.dispatchTouchEvent(down);
            root.dispatchTouchEvent(up);
        } catch (Throwable ignored) {
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static boolean hasVisibleText(View root, String label) {
        ArrayList<TextView> views = new ArrayList<>();
        collectTextViews(root, views);
        for (TextView view : views) {
            if (view.isShown() && normalize(view.getText()).equals(label)) return true;
        }
        return false;
    }

    private static boolean hasVisibleTextContains(View root, String keyword) {
        ArrayList<TextView> views = new ArrayList<>();
        collectTextViews(root, views);
        for (TextView view : views) {
            if (view.isShown() && normalize(view.getText()).contains(keyword)) return true;
        }
        return false;
    }

    private static void collectTextViews(View root, ArrayList<TextView> out) {
        if (root == null) return;
        if (root instanceof TextView) out.add((TextView) root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTextViews(group.getChildAt(i), out);
            }
        }
    }

    private static void collectViews(View root, ArrayList<View> out) {
        if (root == null) return;
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectViews(group.getChildAt(i), out);
            }
        }
    }

    /** 失败时把当前可见文案写进日志，方便适配新版界面。 */
    public static String dumpTexts(Activity activity) {
        ArrayList<TextView> views = new ArrayList<>();
        collectTextViews(activity.getWindow().getDecorView(), views);
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (TextView view : views) {
            if (!view.isShown()) continue;
            String text = normalize(view.getText());
            if (text.length() == 0) continue;
            if (builder.length() > 0) builder.append(" | ");
            builder.append(text.length() > 24 ? text.substring(0, 24) : text);
            if (++count >= 20) break;
        }
        return builder.toString();
    }

    private static String normalize(CharSequence text) {
        if (text == null) return "";
        return text.toString().replaceAll("[\\s\\u00A0\\u3000]+", "").trim();
    }

    static void toast(Activity activity, String text) {
        try {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
