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

    public static final String ACTION_NONE = "none";
    public static final String ACTION_KNOW = "know";
    public static final String ACTION_VAGUE = "vague";
    public static final String ACTION_FORGET = "forget";
    public static final String ACTION_FAMILIAR = "familiar";
    public static final String ACTION_PRONOUNCE = "pronounce";
    public static final String ACTION_SHOW = "show";
    public static final String ACTION_PEEK = "peek";
    public static final String ACTION_PREV = "prev";

    /** 设置页下拉框顺序 */
    public static final String[] ACTION_KEYS = {
            ACTION_NONE, ACTION_KNOW, ACTION_VAGUE, ACTION_FORGET, ACTION_FAMILIAR,
            ACTION_PRONOUNCE, ACTION_SHOW, ACTION_PEEK, ACTION_PREV
    };
    public static final String[] ACTION_LABELS = {
            "无操作", "认识", "模糊", "忘记", "熟知", "发音", "显示单词", "瞄一眼", "返回上一个单词"
    };

    /** 手势顺序：单击、上滑、下滑、左滑、右滑 */
    public static final String[] GESTURE_LABELS = {"单击", "上滑", "下滑", "左滑", "右滑"};

    public static final String[] DEFAULT_BEFORE = {
            ACTION_SHOW, ACTION_PRONOUNCE, ACTION_PEEK, ACTION_PREV, ACTION_NONE
    };
    public static final String[] DEFAULT_AFTER = {
            ACTION_KNOW, ACTION_FAMILIAR, ACTION_FORGET, ACTION_VAGUE, ACTION_PREV
    };

    public boolean enabled = true;
    public boolean locked = false;
    public boolean longPressLock = true;
    public boolean haptic = true;
    public boolean diagnose = true;
    public int peekMs = 1600;
    /** 相对初始位置的偏移，按屏幕宽高归一化，换分辨率不跑偏。 */
    public float offsetXRatio = 0f;
    public float offsetYRatio = 0f;
    public String[] before = DEFAULT_BEFORE.clone();
    public String[] after = DEFAULT_AFTER.clone();

    private static volatile Config cached;

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
    }

    public static void invalidate() {
        cached = null;
    }

    public void reset() {
        enabled = true;
        locked = false;
        longPressLock = true;
        haptic = true;
        diagnose = true;
        peekMs = 1600;
        offsetXRatio = 0f;
        offsetYRatio = 0f;
        before = DEFAULT_BEFORE.clone();
        after = DEFAULT_AFTER.clone();
    }

    public String actionFor(boolean answerShown, int gestureIndex) {
        String[] array = answerShown ? after : before;
        if (array == null || gestureIndex < 0 || gestureIndex >= array.length) {
            return ACTION_NONE;
        }
        return array[gestureIndex];
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", enabled);
            o.put("locked", locked);
            o.put("longPressLock", longPressLock);
            o.put("haptic", haptic);
            o.put("diagnose", diagnose);
            o.put("peekMs", peekMs);
            o.put("offsetXRatio", (double) offsetXRatio);
            o.put("offsetYRatio", (double) offsetYRatio);
            o.put("before", new JSONArray(Arrays.asList(before)));
            o.put("after", new JSONArray(Arrays.asList(after)));
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
            c.longPressLock = o.optBoolean("longPressLock", c.longPressLock);
            c.haptic = o.optBoolean("haptic", c.haptic);
            c.diagnose = o.optBoolean("diagnose", c.diagnose);
            c.peekMs = clamp(o.optInt("peekMs", c.peekMs), 600, 5000);
            c.offsetXRatio = clamp((float) o.optDouble("offsetXRatio", c.offsetXRatio), -3f, 3f);
            c.offsetYRatio = clamp((float) o.optDouble("offsetYRatio", c.offsetYRatio), -3f, 3f);
            c.before = readArray(o.optJSONArray("before"), DEFAULT_BEFORE);
            c.after = readArray(o.optJSONArray("after"), DEFAULT_AFTER);
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static String[] readArray(JSONArray array, String[] fallback) {
        if (array == null || array.length() != 5) return fallback.clone();
        String[] out = new String[5];
        for (int i = 0; i < 5; i++) {
            String key = array.optString(i, ACTION_NONE);
            if (!isValidAction(key)) return fallback.clone();
            out[i] = key;
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
