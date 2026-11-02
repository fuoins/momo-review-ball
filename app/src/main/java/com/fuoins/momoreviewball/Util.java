package com.fuoins.momoreviewball;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

/** 通用小工具。 */
final class Util {

    private Util() {
    }

    static Activity findActivity(Context context) {
        Context c = context;
        int guard = 0;
        while (c instanceof ContextWrapper && guard++ < 10) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
