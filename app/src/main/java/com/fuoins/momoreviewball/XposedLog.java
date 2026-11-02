package com.fuoins.momoreviewball;

/**
 * XposedBridge.log 的安全包装：设置页（普通应用进程）里 XposedBridge 不存在，直接调用会崩。
 */
final class XposedLog {

    private XposedLog() {
    }

    static void i(String msg) {
        log("I", msg, null);
    }

    static void d(String msg) {
        log("D", msg, null);
    }

    static void w(String msg) {
        log("W", msg, null);
    }

    static void w(String msg, Throwable t) {
        log("W", msg + ": " + t, t);
    }

    static void e(String msg, Throwable t) {
        log("E", msg + ": " + t, t);
    }

    private static void log(String level, String msg, Throwable t) {
        try {
            de.robv.android.xposed.XposedBridge.log("MoMoBall[" + level + "] " + msg);
        } catch (Throwable ignored) {
        }
        try {
            if ("E".equals(level)) {
                android.util.Log.e("MoMoBall", msg, t);
            } else if ("W".equals(level)) {
                android.util.Log.w("MoMoBall", msg, t);
            } else if ("D".equals(level)) {
                android.util.Log.d("MoMoBall", msg, t);
            } else {
                android.util.Log.i("MoMoBall", msg, t);
            }
        } catch (Throwable ignored) {
        }
    }
}
