package com.fuoins.momoreviewball;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * 模块配置。设置界面运行在墨墨进程内，因此直接读写墨墨私有目录下的 SharedPreferences，
 * 不需要跨进程通信，配置改动对悬浮球立即生效。
 */
public final class Config {

    static final String SP_NAME = "momo_ball_config";
    private static final String KEY_JSON = "config";
    private static final String KEY_NATIVE_BALL_DP = "native_ball_dp";
    /** 配置结构版本：2 起带这个字段，用于区分 3.0.x 存下的旧配置。 */
    private static final int SCHEMA = 2;

    public static final String ACTION_NONE = "none";
    public static final String ACTION_KNOW = "know";
    public static final String ACTION_VAGUE = "vague";
    public static final String ACTION_FORGET = "forget";
    public static final String ACTION_FAMILIAR = "familiar";
    public static final String ACTION_PRONOUNCE = "pronounce";
    public static final String ACTION_SHOW = "show";
    /** 官方「瞄一眼」：答案出现前原生悬浮球左滑触发的例句提示，不是模块自己画的气泡。 */
    public static final String ACTION_PEEK = "peek";
    public static final String ACTION_PREV = "prev";

    /** 全部动作。数组顺序固定，球心短标签按同一顺序取。 */
    public static final String[] ACTION_KEYS = {
            ACTION_NONE, ACTION_KNOW, ACTION_VAGUE, ACTION_FORGET, ACTION_FAMILIAR,
            ACTION_PRONOUNCE, ACTION_SHOW, ACTION_PEEK, ACTION_PREV
    };
    public static final String[] ACTION_LABELS = {
            "无操作", "认识", "模糊", "忘记", "熟知", "发音", "显示单词", "瞄一眼",
            "返回上一个单词"
    };

    /**
     * 「单词出现前」能选的动作。
     *
     * <p>答案还没出来时根本没有「认识 / 模糊 / 忘记 / 熟知」这些按钮，选它们只会点空，
     * 所以这里按阶段给两套独立的候选，而不是拿一张大表糊弄。
     */
    public static final String[] BEFORE_ACTIONS = {
            ACTION_SHOW, ACTION_PEEK, ACTION_PRONOUNCE, ACTION_PREV, ACTION_NONE
    };
    /** 「单词出现后」能选的动作。答案已经显示，瞄一眼/显示单词都无事可做。 */
    public static final String[] AFTER_ACTIONS = {
            ACTION_KNOW, ACTION_VAGUE, ACTION_FORGET, ACTION_FAMILIAR,
            ACTION_PRONOUNCE, ACTION_PREV, ACTION_NONE
    };

    /** 悬浮球自定义大小范围，1.0 = 墨墨原生尺寸。 */
    public static final float SCALE_MIN = 0.6f;
    public static final float SCALE_MAX = 2.0f;

    /** 手势顺序：单击、上滑、下滑、左滑、右滑 */
    public static final String[] GESTURE_LABELS = {"单击", "上滑", "下滑", "左滑", "右滑"};

    public static final String[] DEFAULT_BEFORE = {
            ACTION_SHOW, ACTION_PRONOUNCE, ACTION_PEEK, ACTION_PREV, ACTION_NONE
    };
    public static final String[] DEFAULT_AFTER = {
            ACTION_KNOW, ACTION_FAMILIAR, ACTION_FORGET, ACTION_VAGUE, ACTION_PREV
    };

    public boolean enabled = true;
    /** 锁定后悬浮球不可拖动，位置与拖动边界仍由墨墨自己管理。 */
    public boolean locked = false;
    /** 悬浮球缩放，1.0 为原生大小；由设置页滑杆调节，直接作用在球控件上。 */
    public float scale = 1f;
    /**
     * 映射里用到「熟知」时，自动把复习页底部的「熟知」按钮显示出来。
     *
     * <p>墨墨默认关闭「标识熟知」，按钮通常还在视图树里，只是被设为 GONE；
     * 模块可以直接把它露出来，省得用户再跑一趟「更多设置 → 复习页面 → 工具栏快捷」。
     */
    public boolean autoFamiliar = true;
    public String[] before = DEFAULT_BEFORE.clone();
    public String[] after = DEFAULT_AFTER.clone();
    /** 用户改过的动作颜色：key 为动作，value 为 ARGB；没改过的走 {@link #defaultColorOf}。 */
    public final java.util.LinkedHashMap<String, Integer> colors = new java.util.LinkedHashMap<>();

    private static volatile Config cached;
    /** 每次保存 +1，悬浮球据此判断配色/映射是否变了，不用等下一个轮询周期。 */
    private static volatile int revision = 1;

    public static int revision() {
        return revision;
    }

    public static Config defaults() {
        return new Config();
    }

    public static Config get(Context context) {
        Config c = cached;
        if (c != null) return c;
        c = load(context);
        cached = c;
        return c;
    }

    private static Config load(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            String json = sp.getString(KEY_JSON, null);
            if (json != null && json.length() > 0) return fromJson(json);
        } catch (Throwable ignored) {
        }
        return defaults();
    }

    public void save(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_JSON, toJson()).apply();
        } catch (Throwable ignored) {
        }
        cached = this;
        revision++;
    }

    public static void invalidate() {
        cached = null;
    }

    public void reset() {
        enabled = true;
        locked = false;
        scale = 1f;
        autoFamiliar = true;
        before = DEFAULT_BEFORE.clone();
        after = DEFAULT_AFTER.clone();
        colors.clear();
    }

    /** 动作的默认配色（墨墨自带的那套）。 */
    public static int defaultColorOf(String key) {
        switch (key) {
            case ACTION_KNOW:
                return 0xFF4CAF50;   // 绿
            case ACTION_VAGUE:
                return 0xFFF2A33C;   // 橙
            case ACTION_FORGET:
                return 0xFFE53935;   // 红
            case ACTION_FAMILIAR:
                return 0xFF00897B;   // 青
            case ACTION_PRONOUNCE:
                return 0xFF8E44AD;   // 紫
            case ACTION_PREV:
                return 0xFF9E9E9E;   // 灰
            case ACTION_SHOW:
                return 0xFF546E7A;   // 蓝灰
            case ACTION_PEEK:
                return 0xFF3E8ED0;   // 蓝
            default:
                return 0xFFB9C0CC;   // 无操作
        }
    }

    /** 当前生效的颜色：用户设过就用用户的，否则用默认。 */
    public int colorOf(String key) {
        Integer custom = colors.get(key);
        return custom == null ? defaultColorOf(key) : custom;
    }

    public boolean hasCustomColor(String key) {
        return colors.containsKey(key);
    }

    public void setColor(String key, int color) {
        if (color == defaultColorOf(key)) {
            colors.remove(key);
        } else {
            colors.put(key, color | 0xFF000000);
        }
    }

    public void resetColor(String key) {
        colors.remove(key);
    }

    public void resetColors() {
        colors.clear();
    }

    /** 当前映射里是否用到「熟知」。 */
    public boolean usesFamiliar() {
        return contains(before, ACTION_FAMILIAR) || contains(after, ACTION_FAMILIAR);
    }

    private static boolean contains(String[] actions, String key) {
        if (actions == null) return false;
        for (String action : actions) {
            if (key.equals(action)) return true;
        }
        return false;
    }

    public String actionFor(boolean answerShown, int gestureIndex) {
        String[] array = answerShown ? after : before;
        if (array == null || gestureIndex < 0 || gestureIndex >= array.length) {
            return ACTION_NONE;
        }
        return array[gestureIndex];
    }

    /** 某个阶段可选的动作用于生成下拉框。 */
    public static String[] actionsFor(boolean after) {
        return after ? AFTER_ACTIONS : BEFORE_ACTIONS;
    }

    public static boolean allows(boolean after, String key) {
        for (String option : actionsFor(after)) {
            if (option.equals(key)) return true;
        }
        return false;
    }

    public static String labelOf(String key) {
        for (int i = 0; i < ACTION_KEYS.length; i++) {
            if (ACTION_KEYS[i].equals(key)) return ACTION_LABELS[i];
        }
        return key;
    }

    public static int indexOf(String[] options, String key) {
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(key)) return i;
        }
        return options.length - 1;   // 末位永远是「无操作」
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", enabled);
            o.put("locked", locked);
            o.put("scale", (double) scale);
            o.put("autoFamiliar", autoFamiliar);
            o.put("schema", SCHEMA);
            o.put("before", new JSONArray(Arrays.asList(before)));
            o.put("after", new JSONArray(Arrays.asList(after)));
            if (!colors.isEmpty()) {
                JSONObject c = new JSONObject();
                for (java.util.Map.Entry<String, Integer> e : colors.entrySet()) {
                    c.put(e.getKey(), (int) e.getValue());
                }
                o.put("colors", c);
            }
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    public static Config fromJson(String json) {
        Config c = new Config();
        if (json == null || json.length() == 0) return c;
        try {
            JSONObject o = new JSONObject(json);
            c.enabled = o.optBoolean("enabled", c.enabled);
            c.locked = o.optBoolean("locked", c.locked);
            c.autoFamiliar = o.optBoolean("autoFamiliar", true);
            c.scale = clamp((float) o.optDouble("scale", 1.0), SCALE_MIN, SCALE_MAX);
            c.before = readArray(o.optJSONArray("before"), DEFAULT_BEFORE, false);
            c.after = readArray(o.optJSONArray("after"), DEFAULT_AFTER, true);
            JSONObject colors = o.optJSONObject("colors");
            if (colors != null) {
                java.util.Iterator<String> it = colors.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    if (!isValidAction(key)) continue;
                    int value = colors.optInt(key, 0);
                    if ((value >>> 24) == 0) value |= 0xFF000000;
                    if (value != defaultColorOf(key)) c.colors.put(key, value);
                }
            }

            // 3.0.x 把「瞄一眼」整条拿掉时，默认配置里的下滑被折成了「无操作」。
            // 这里只认「其余四项还是原样」的默认形态，把下滑补回瞄一眼；
            // 用户自己改过的手势一律不动。
            if (!o.has("schema")
                    && ACTION_NONE.equals(c.before[2])
                    && ACTION_PRONOUNCE.equals(c.before[1])
                    && ACTION_PREV.equals(c.before[3])) {
                c.before[2] = ACTION_PEEK;
            }
        } catch (Throwable ignored) {
        }
        return c;
    }

    /** 真球直径的进程内缓存，避免每帧都读一遍 SharedPreferences。 */
    private static volatile float cachedNativeBallDp;

    /**
     * 真球的物理直径（dp）。
     *
     * <p>墨墨的球是 dp 尺寸，各机型 px 不同；{@link BallOverlay} 第一次画的时候
     * 把实测值记下来，设置页的预览按同一尺寸画，滑杆 1.0 才真的等于复习页那颗球。
     */
    static float nativeBallDiameterDp(Context context) {
        float fallback = 74.7f;
        float cached = cachedNativeBallDp;
        if (cached > 0f) return cached;
        try {
            SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            float value = sp.getFloat(KEY_NATIVE_BALL_DP, fallback);
            cachedNativeBallDp = value;
            return value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    static void rememberNativeBallDiameterDp(Context context, float dp) {
        if (dp < 20f || dp > 200f) return;
        if (Math.abs(cachedNativeBallDp - dp) < 0.5f) return;
        cachedNativeBallDp = dp;
        try {
            SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            sp.edit().putFloat(KEY_NATIVE_BALL_DP, dp).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 解析一个阶段的手势数组。
     *
     * <p>旧版本存过「瞄一眼」以及「出现前用认识」这类组合，这里只把不合法的那一项
     * 归零，不整组重置——否则升级一次就把用户配好的五个手势全清了。
     */
    private static String[] readArray(JSONArray array, String[] fallback, boolean after) {
        if (array == null || array.length() != 5) return fallback.clone();
        String[] out = new String[5];
        for (int i = 0; i < 5; i++) {
            String key = array.optString(i, ACTION_NONE);
            out[i] = allows(after, key) ? key : ACTION_NONE;
        }
        return out;
    }

    public static boolean isValidAction(String key) {
        for (String k : ACTION_KEYS) {
            if (k.equals(key)) return true;
        }
        return false;
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
