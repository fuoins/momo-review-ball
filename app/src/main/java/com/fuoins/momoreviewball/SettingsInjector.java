package com.fuoins.momoreviewball;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 把模块设置入口注入墨墨的「手势悬浮球」设置页
 * （{@code settings.study.StudyGestureActivity}）。
 *
 * <p>用右下角一个小胶囊按钮作为入口，不改变页面原有布局。
 */
public final class SettingsInjector {

    private static final String ACTIVITY = "com.maimemo.android.momo.settings.study.StudyGestureActivity";
    private static final String TAG = "momo_ball_settings_entry";
    private static final int ORANGE = 0xFFF2A33C;

    private SettingsInjector() {
    }

    public static void maybeInject(final Activity activity) {
        if (activity == null) return;
        if (!isTarget(activity)) return;

        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> inject(activity));
            return;
        }
        inject(activity);
    }

    private static boolean isTarget(Activity activity) {
        String name = activity.getClass().getName();
        return ACTIVITY.equals(name) || name.endsWith(".settings.study.StudyGestureActivity");
    }

    private static void inject(final Activity activity) {
        try {
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) content;
            if (root.findViewWithTag(TAG) != null) return;

            TextView button = new TextView(activity);
            button.setTag(TAG);
            button.setText("模块设置");
            button.setTextColor(Color.WHITE);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            button.setGravity(Gravity.CENTER);
            button.setPadding(Util.dp(activity, 16), Util.dp(activity, 9),
                    Util.dp(activity, 16), Util.dp(activity, 9));
            button.setElevation(Util.dp(activity, 4));

            GradientDrawable background = new GradientDrawable();
            background.setColor(ORANGE);
            background.setCornerRadius(Util.dp(activity, 20));
            button.setBackground(background);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.END | Gravity.BOTTOM;
            lp.rightMargin = Util.dp(activity, 16);
            lp.bottomMargin = Util.dp(activity, 96);
            button.setLayoutParams(lp);

            button.setOnClickListener(v -> SettingsPanel.show(activity));

            root.addView(button);
            button.bringToFront();
            XposedLog.i("已在手势设置页注入入口");
        } catch (Throwable t) {
            XposedLog.e("注入设置入口失败", t);
        }
    }
}
