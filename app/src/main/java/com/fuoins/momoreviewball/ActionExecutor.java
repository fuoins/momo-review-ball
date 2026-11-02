package com.fuoins.momoreviewball;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 动作执行器。
 *
 * 墨墨 5.5.25 使用了加固，内部方法无法静态定位；这里改为在复习页的视图树里
 * 查找对应的按钮/文案并触发点击，不依赖加固后的类名，升级小版本也能用。
 */
public final class ActionExecutor {

    private static final String TAG = "MoMoBall";

    private static final Pattern ENGLISH_WORD =
            Pattern.compile("^[A-Za-z][A-Za-z'’\\-]*(?: [A-Za-z][A-Za-z'’\\-]*){0,2}$");

    private static final String[] UI_BLACKLIST = {
            "认识", "模糊", "忘记", "熟知", "发音", "显示单词", "瞄一眼", "上一个",
            "返回上一个单词", "墨墨背单词", "复习", "设置", "取消", "确定", "完成",
            "认识单词", "不认识"
    };

    private static WeakReference<TextView> peekViewRef;

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

    private static boolean peek(Activity activity) {
        if (clickId(activity, "btn_peek_handwriting", "btn_peek_keyboard")) return true;
        if (clickText(activity, new String[]{"瞄一眼"}, true)) {
            return true;
        }
        String word = extractWord(activity);
        if (!TextUtils.isEmpty(word)) {
            showPeekOverlay(activity, word);
            return true;
        }
        return false;
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

    /**
     * 从视图树里猜当前单词：取字号最大的纯英文文本。
     * 用于「瞄一眼」在应用本身没有可见按钮时，用悬浮气泡短暂显示。
     */
    private static String extractWord(Activity activity) {
        ArrayList<TextView> views = new ArrayList<>();
        collectTextViews(activity.getWindow().getDecorView(), views);
        String best = null;
        float bestSize = -1f;
        for (TextView view : views) {
            if (view.getVisibility() == View.GONE) continue;
            CharSequence raw = view.getText();
            if (raw == null) continue;
            String text = raw.toString().trim();
            if (text.length() < 2 || text.length() > 32) continue;
            if (!ENGLISH_WORD.matcher(text).matches()) continue;
            if (isBlacklisted(text)) continue;
            if (text.equalsIgnoreCase("vip") || text.equalsIgnoreCase("ok")
                    || text.equalsIgnoreCase("pdf")) {
                continue;
            }
            float size = view.getTextSize();
            if (size > bestSize) {
                bestSize = size;
                best = text;
            }
        }
        return best;
    }

    private static boolean isBlacklisted(String text) {
        for (String s : UI_BLACKLIST) {
            if (text.equals(s)) return true;
        }
        return false;
    }

    private static void showPeekOverlay(Activity activity, String word) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;

        TextView old = peekViewRef == null ? null : peekViewRef.get();
        if (old != null && old.getParent() == root) {
            root.removeView(old);
        }

        TextView view = new TextView(activity);
        view.setText(word);
        view.setTextSize(30);
        view.setTextColor(Color.WHITE);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        int padH = dp(activity, 22);
        int padV = dp(activity, 14);
        view.setPadding(padH, padV, padH, padV);
        view.setBackground(roundRect(0xEE2F3E6E, dp(activity, 18)));
        view.setElevation(dp(activity, 12));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        try {
            root.addView(view, lp);
        } catch (Throwable t) {
            return;
        }
        peekViewRef = new WeakReference<>(view);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(120).start();

        int duration = Config.Client.get(activity).peekMs;
        view.postDelayed(() -> {
            view.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                try {
                    if (view.getParent() == root) root.removeView(view);
                } catch (Throwable ignored) {
                }
            }).start();
        }, duration);
    }

    private static GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static void toast(Activity activity, String text) {
        try {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
