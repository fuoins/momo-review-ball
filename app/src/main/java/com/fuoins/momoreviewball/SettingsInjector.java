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

            // 首选：把页面列表搬进同一个滚动容器，模块配置紧接其后，
            // 整页只有一个滚动区，看起来就是原生设置项的延续
            if (mergeIntoScroll(activity, root, panel)) {
                return true;
            }

            // 次选：页面本身就有滚动容器，直接追加到内容末尾
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
                    XposedLog.w("内联失败：" + t, t);
                }
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

    /**
     * 把页面列表和模块配置并进同一个滚动区。
     *
     * <p>分两种情况：
     * <ul>
     *   <li>页面本来就有滚动容器：把 RecyclerView 搬进它顶部，配置接在末尾；</li>
     *   <li>页面没有滚动容器（墨墨 5.6 就是这样，只有绝对定位的 RecyclerView）：
     *       新建一个 ScrollView 顶在列表原位，把「列表 + 配置」一起装进去。</li>
     * </ul>
     * 两种结果都是整页只有一个滚动区，配置紧接原生项，不再分成上下两块。
     */
    private static boolean mergeIntoScroll(Activity activity, ViewGroup root, View panel) {
        try {
            View recycler = findRecycler(root);
            if (recycler == null) return false;

            View existing = findScrollView(root);
            if (existing instanceof ViewGroup
                    && mergeIntoExisting((ViewGroup) existing, recycler, panel)) {
                return true;
            }
            return wrapRecycler(activity, recycler, panel);
        } catch (Throwable t) {
            XposedLog.w("合并滚动区失败：" + t, t);
            return false;
        }
    }

    /** 页面已有滚动容器：把列表搬到它顶部，配置追加到末尾。 */
    private static boolean mergeIntoExisting(ViewGroup scrollGroup, View recycler, View panel) {
        if (scrollGroup.getChildCount() == 0) return false;
        View content = scrollGroup.getChildAt(0);
        if (!(content instanceof LinearLayout)) return false;
        LinearLayout column = (LinearLayout) content;

        if (recycler.getParent() != column) {
            int recyclerTop = recycler.getTop();
            int scrollTop = scrollGroup.getTop();

            ViewGroup oldParent = (ViewGroup) recycler.getParent();
            if (oldParent != null) oldParent.removeView(recycler);
            stopNestedScroll(recycler);

            column.addView(recycler, 0, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 滚动容器上移，填掉列表腾出来的位置，避免中间空一大块
            ViewGroup.LayoutParams slp = scrollGroup.getLayoutParams();
            if (slp instanceof FrameLayout.LayoutParams && recyclerTop < scrollTop) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) slp;
                int delta = scrollTop - recyclerTop;
                flp.topMargin = Math.max(0, flp.topMargin - delta);
                if (flp.height > 0) flp.height += delta;
                scrollGroup.setLayoutParams(flp);
            }
        }

        column.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        XposedLog.i("已合并到页面滚动区，容器子项=" + column.getChildCount());
        return true;
    }

    /**
     * 页面没有滚动容器时自建一个：顶替 RecyclerView 原来的位置，
     * 上面是原生列表、下面是模块配置，一起滚动。
     */
    private static boolean wrapRecycler(Activity activity, View recycler, View panel) {
        ViewGroup parent = (ViewGroup) recycler.getParent();
        if (parent == null) return false;
        // 只处理能直接给子 View 定绝对位置的容器，约束布局硬塞会跑到左上角
        if (!(parent instanceof FrameLayout) && !(parent instanceof LinearLayout)) return false;

        int index = parent.indexOfChild(recycler);
        int top = recycler.getTop() - parent.getPaddingTop();
        int limit = availableBottom(parent, recycler);
        int height = limit - recycler.getTop();
        if (height <= 0) return false;

        parent.removeView(recycler);
        stopNestedScroll(recycler);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(recycler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ViewGroup.LayoutParams lp;
        if (parent instanceof FrameLayout) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height);
            flp.topMargin = Math.max(0, top);
            lp = flp;
        } else {
            lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        }
        parent.addView(scroll, Math.max(0, Math.min(index, parent.getChildCount())), lp);

        XposedLog.i("已自建滚动区承载配置，top=" + top + " height=" + height
                + "，父容器=" + parent.getClass().getSimpleName());
        return true;
    }

    /** 列表下方最近的兄弟 View 视为可用区域的下边界（比如底部那个「禁用」开关）。 */
    private static int availableBottom(ViewGroup parent, View recycler) {
        int limit = parent.getHeight() - parent.getPaddingBottom();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View sibling = parent.getChildAt(i);
            if (sibling == recycler) continue;
            int siblingTop = sibling.getTop();
            if (siblingTop > recycler.getBottom() && siblingTop < limit) {
                limit = siblingTop;
            }
        }
        return limit;
    }

    private static void stopNestedScroll(View recycler) {
        try {
            recycler.setNestedScrollingEnabled(false);
        } catch (Throwable ignored) {
        }
    }

    private static View findRecycler(View view) {
        if (view == null) return null;
        if (view.getClass().getName().endsWith("RecyclerView")) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findRecycler(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ViewGroup findScrollView(View view) {
        if (view == null) return null;
        String name = view.getClass().getName();
        if (name.endsWith("ScrollView") && !name.endsWith("HorizontalScrollView")) {
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
