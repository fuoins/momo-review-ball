package com.fuoins.momoreviewball;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * 模块配置。设置页与注入端共用同一份 JSON 结构，通过 ContentProvider 跨进程读取。
 */
public final class Config {

    public static final String AUTHORITY = "com.fuoins.momoreviewball.settings";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");

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
    public int sizeDp = 52;
    public int alpha = 88;
    public boolean locked = false;
    public boolean longPressLock = true;
    public boolean haptic = true;
    public boolean diagnose = true;
    public float xRatio = 0.92f;
    public float yRatio = 0.74f;
    public int peekMs = 1600;
    public String[] before = DEFAULT_BEFORE.clone();
    public String[] after = DEFAULT_AFTER.clone();

    public static Config defaults() {
        return new Config();
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
            o.put("sizeDp", sizeDp);
            o.put("alpha", alpha);
            o.put("locked", locked);
            o.put("longPressLock", longPressLock);
            o.put("haptic", haptic);
            o.put("diagnose", diagnose);
            o.put("xRatio", (double) xRatio);
            o.put("yRatio", (double) yRatio);
            o.put("peekMs", peekMs);
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
            c.sizeDp = clamp(o.optInt("sizeDp", c.sizeDp), 40, 72);
            c.alpha = clamp(o.optInt("alpha", c.alpha), 25, 100);
            c.locked = o.optBoolean("locked", c.locked);
            c.longPressLock = o.optBoolean("longPressLock", c.longPressLock);
            c.haptic = o.optBoolean("haptic", c.haptic);
            c.diagnose = o.optBoolean("diagnose", c.diagnose);
            c.xRatio = clamp((float) o.optDouble("xRatio", c.xRatio), 0f, 1f);
            c.yRatio = clamp((float) o.optDouble("yRatio", c.yRatio), 0f, 1f);
            c.peekMs = clamp(o.optInt("peekMs", c.peekMs), 600, 5000);
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

    /**
     * 注入端读取配置。模块 App 未启动 / 未安装时回退到默认值。
     */
    public static final class Client {
        private static volatile Config cached;
        private static volatile long cachedAt;
        private static volatile long cachedTtl = 700L;
        private static volatile String lastSource = "unknown";

        private Client() {
        }

        /** 最近一次配置读取的来源，用于诊断模块与设置页之间的跨进程链路。 */
        public static String describeSource() {
            return lastSource;
        }

        public static Config get(Context context) {
            long now = SystemClock.elapsedRealtime();
            Config c = cached;
            if (c != null && now - cachedAt < cachedTtl) {
                return c;
            }
            c = null;
            long ttl = 5000L;
            String source;
            try {
                Bundle bundle = context.getContentResolver().call(URI, "get", null, null);
                if (bundle != null && bundle.getString("json") != null) {
                    c = fromJson(bundle.getString("json"));
                    ttl = 2000L;
                    source = "provider";
                } else {
                    source = "provider-empty";
                }
            } catch (Throwable t) {
                source = "provider-error(" + t.getClass().getSimpleName() + ")";
            }
            if (c == null) {
                c = defaults();
                if ("provider".equals(source) || "provider-empty".equals(source)) {
                    source = source + "+default";
                }
            }
            lastSource = source;
            cached = c;
            cachedAt = now;
            cachedTtl = ttl;
            return c;
        }

        public static void invalidate() {
            cached = null;
        }

        public static void setPosition(Context context, float x, float y) {
            try {
                Bundle args = new Bundle();
                args.putFloat("x", clamp(x, 0f, 1f));
                args.putFloat("y", clamp(y, 0f, 1f));
                context.getContentResolver().call(URI, "setPosition", null, args);
            } catch (Throwable ignored) {
            }
            invalidate();
        }

        public static void setLocked(Context context, boolean locked) {
            try {
                Bundle args = new Bundle();
                args.putBoolean("locked", locked);
                context.getContentResolver().call(URI, "setLocked", null, args);
            } catch (Throwable ignored) {
            }
            invalidate();
        }
    }
}
