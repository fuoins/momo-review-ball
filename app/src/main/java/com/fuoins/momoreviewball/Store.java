package com.fuoins.momoreviewball;

import android.content.Context;
import android.content.SharedPreferences;

/** 设置页所在进程的配置读写。 */
public final class Store {

    private static final String PREFS = "config";
    private static final String KEY_JSON = "json";

    private Store() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Config load(Context context) {
        return Config.fromJson(prefs(context).getString(KEY_JSON, ""));
    }

    public static void save(Context context, Config config) {
        prefs(context).edit().putString(KEY_JSON, config.toJson()).apply();
        context.getContentResolver().notifyChange(Config.URI, null);
    }
}
