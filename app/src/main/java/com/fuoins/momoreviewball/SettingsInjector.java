package com.fuoins.momoreviewball;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;

/**
 * 把模块配置直接铺进墨墨的「手势悬浮球」设置页
 * （{@code settings.study.StudyGestureActivity}）。
 *
 * <p>不弹新页面、不需要点按钮：找到页面自己的滚动容器，把配置块追加到它的内容末尾，
 * 跟页面一起滚动。只有在页面结构完全认不出来时，才退化成右下角一个浮动入口。
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

        // 等页面把内容铺完再塞；布局没稳时先挂一次全局布局监听
        root.postDelayed(() -> tryAttach(activity, root), 320L);
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (tryAttach(activity, root)) {
                            try {
                                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
    }

    /** @return true 表示已经处理完（注入成功或已存在），不需要再重试。 */
    private static boolean tryAttach(Activity activity, ViewGroup root) {
        try {
            if (root.findViewWithTag(TAG) != null) return true;

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
                    XposedLog.i("已内联模块配置，容器=" + parent.getClass().getName()
                            + "，子项数=" + parent.getChildCount());
                    return true;
                } catch (Throwable t) {
                    XposedLog.w("内联失败，改用分区布局：" + t, t);
                }
            }

            // 页面根是 ConstraintLayout / RecyclerView 这类没法直接 append 的容器时，
            // 把整页包进一个竖向 LinearLayout：上面原页面，下面模块设置，互不干扰。
            if (wrapPage(activity, root, panel)) {
                XposedLog.i("已把模块配置铺在设置页下方，页面结构：" + describe(root));
                return true;
            }

            XposedLog.w("没找到可插入的位置，页面结构：" + describe(root));
            addFloatingButton(activity, root);
            return true;
        } catch (Throwable t) {
            XposedLog.e("注入设置失败", t);
            return false;
        }
    }

    /** 页面内容容器：优先滚动容器里的内容布局，其次第一个竖向 LinearLayout。 */
    private static ViewGroup findContainer(View root) {
        ViewGroup scroll = findScrollView(root);
        if (scroll != null) {
            for (int i = 0; i < scroll.getChildCount(); i++) {
                View child = scroll.getChildAt(i);
                if (child instanceof LinearLayout) return (LinearLayout) child;
            }
            if (scroll.getChildCount() == 0) return scroll;
        }
        return findVerticalLinear(root);
    }

    /** 上下分区：原页面占满剩余空间，模块配置固定在下方，可单独滚动。 */
    private static boolean wrapPage(Activity activity, ViewGroup root, View panel) {
        try {
            if (root.getChildCount() != 1) return false;
            View page = root.getChildAt(0);
            if (page == null) return false;

            root.removeViewAt(0);

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            root.addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            column.addView(page, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            ScrollView box = new ScrollView(activity);
            box.setBackgroundColor(0xFFFFFFFF);
            box.setElevation(Util.dp(activity, 8));
            box.addView(panel, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.46f);
            column.addView(box, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height));
            return true;
        } catch (Throwable t) {
            XposedLog.w("分区布局失败：" + t, t);
            return false;
        }
    }

    private static ViewGroup findScrollView(View view) {
        if (view == null) return null;
        String name = view.getClass().getName();
        if (name.endsWith("ScrollView")) {
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

    /** 广度优先找第一个装了多个子项的竖向 LinearLayout。 */
    private static ViewGroup findVerticalLinear(View root) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view instanceof LinearLayout) {
                LinearLayout ll = (LinearLayout) view;
                if (ll.getOrientation() == LinearLayout.VERTICAL
                        && ll.getChildCount() >= 2
                        && ll != root) {
                    return ll;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return null;
    }

    /** 只打印类名和层级，便于定位页面结构。 */
    private static String describe(View root) {
        StringBuilder sb = new StringBuilder();
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int count = 0;
        while (!queue.isEmpty() && count < 26) {
            View view = queue.poll();
            sb.append(view.getClass().getSimpleName()).append('(')
                    .append(view.getWidth()).append('x').append(view.getHeight()).append(')');
            if (view.getTag() != null) sb.append('#').append(view.getTag());
            sb.append(" | ");
            count++;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount() && i < 12; i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return sb.toString();
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
