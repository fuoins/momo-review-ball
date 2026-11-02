package com.fuoins.momoreviewball;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

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
                ok = clickId(activity, "iv_well_familiar")
                        || clickByDescription(activity, new String[]{"熟知"})
                        || clickText(activity, new String[]{"熟知", "熟悉"}, true);
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

        View root = activity.getWindow().getDecorView();
        boolean know = hasVisibleText(root, "认识");
        boolean forget = hasVisibleText(root, "忘记");
        if (know || forget) return know && forget;
        boolean prompt = hasVisibleTextContains(root, "点击屏幕显示答案")
                || hasVisibleTextContains(root, "请回忆");
        return !prompt;
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
        // ① 个别版本/模式下工具条上会直接出现「瞄一眼」控件，有就点
        if (clickPeekControl(activity)) return true;

        // ② 主路径：把「左滑」交给原生悬浮球
        if (!isAnswerShown(activity) && FloatingButtonHook.replayNative(3)) {
            XposedLog.i("瞄一眼：已把「左滑」交给原生悬浮球");
            // 回放是同步投递、异步生效。350ms 后界面文案一点没变，说明原生没接住
            // （版本差异 / 手势被改过），再补一次 Ctrl+O，避免「回放了但毫无反应」。
            final String before = visibleTextSignature(activity);
            activity.getWindow().getDecorView().postDelayed(() -> {
                try {
                    if (!before.equals(visibleTextSignature(activity))) {
                        XposedLog.i("瞄一眼：左滑回放已生效");
                        return;
                    }
                    XposedLog.w("瞄一眼：左滑回放后界面无变化，改发 Ctrl+O");
                    if (!sendPeekShortcut(activity)) {
                        XposedLog.w("瞄一眼：左滑与 Ctrl+O 都没有反应，可见文案="
                                + dumpTexts(activity));
                        toast(activity, "「瞄一眼」没有响应，可在设置页导出日志反馈");
                    }
                } catch (Throwable ignored) {
                }
            }, 350L);
            return true;
        }

        // ③ 兜底：官方快捷键 Ctrl+O
        boolean ok = sendPeekShortcut(activity);
        if (!ok) {
            XposedLog.w("瞄一眼：控件 / 左滑回放 / Ctrl+O 均不可用；可见文案="
                    + dumpTexts(activity));
        }
        return ok;
    }

    /** 当前可见文案的指纹，用来判断回放后界面到底有没有变化。 */
    private static String visibleTextSignature(Activity activity) {
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
