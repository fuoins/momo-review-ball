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
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 把模块配置直接铺进墨墨的「手势悬浮球」设置页
 * （{@code settings.study.StudyGestureActivity}）。
 *
 * <p>优先内联：找到页面里的滚动容器，把配置卡片追加到它的内容末尾，
 * 跟着页面一起滚动，不需要再点按钮、也不会多出一层页面。
 * 只有当页面结构认不出来（例如换成了 RecyclerView / Compose）时，
 * 才退化成右下角一个「模块设置」浮动按钮。
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

    static boolean isTarget(Activity activity) {
        String name = activity.getClass().getName();
        return ACTIVITY.equals(name) || name.endsWith(".settings.study.StudyGestureActivity");
    }

    private static void inject(final Activity activity) {
        final View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        final ViewGroup root = (ViewGroup) content;
        if (root.findViewWithTag(TAG) != null) return;

        // 等这一帧布局结束再塞，避免拿到还没建好的容器
        root.post(() -> {
            try {
                if (root.findViewWithTag(TAG) != null) return;
                View panel = SettingsPanel.create(activity);
                panel.setTag(TAG);

                ViewGroup parent = findContainer(root);
                if (parent != null) {
                    try {
                        parent.addView(panel);
                        ViewGroup.LayoutParams lp = panel.getLayoutParams();
                        if (lp != null) {
                            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                            panel.setLayoutParams(lp);
                        }
                        XposedLog.i("已内联模块配置到 " + parent.getClass().getName());
                        return;
                    } catch (Throwable t) {
                        XposedLog.w("内联失败，改用浮动入口", t);
                    }
                }
                addFloatingButton(activity, root);
            } catch (Throwable t) {
                XposedLog.e("注入设置失败", t);
            }
        });
    }

    /** 找页面的内容容器：优先滚动容器里那个竖向 LinearLayout。 */
    private static ViewGroup findContainer(View root) {
        ViewGroup scroll = findScrollView(root);
        if (scroll != null) {
            for (int i = 0; i < scroll.getChildCount(); i++) {
                View child = scroll.getChildAt(i);
                if (child instanceof LinearLayout) {
                    return (LinearLayout) child;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof LinearLayout
                        && child.getClass().getName().equals(LinearLayout.class.getName())) {
                    return (LinearLayout) child;
                }
            }
        }
        return null;
    }

    private static ViewGroup findScrollView(View view) {
        if (view == null) return null;
        String name = view.getClass().getName();
        if (name.endsWith("ScrollView") || name.endsWith("NestedScrollView")) {
            return view instanceof ViewGroup ? (ViewGroup) view : null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ViewGroup found = findScrollView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void addFloatingButton(final Activity activity, ViewGroup root) {
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
        XposedLog.i("已在手势设置页注入浮动入口");
    }
}
