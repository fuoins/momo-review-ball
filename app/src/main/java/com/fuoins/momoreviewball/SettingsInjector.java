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
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;

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

    /** 页面是异步铺开的，注入要等；重试这么多次还不行才退回浮动入口。 */
    private static final int MAX_ATTEMPTS = 10;

    /** onResume 和 onWindowFocusChanged 会各触发一次，避免两条重试链并行。 */
    private static final Map<Activity, Boolean> STARTED = new WeakHashMap<>();

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

        synchronized (STARTED) {
            if (STARTED.containsKey(activity)) return;
            STARTED.put(activity, Boolean.TRUE);
        }

        schedule(activity, root, 0);
    }

    /**
     * 定时重试注入。
     *
     * <p>墨墨这个页面是异步铺开的：Activity 起来时 RecyclerView 可能还没 add 进来，
     * 或者父容器还没量出高度。之前只要第一次没命中就立刻退化成右下角「模块设置」按钮，
     * 于是永远看不到内联配置——必须给它几次机会。
     */
    private static void schedule(final Activity activity, final ViewGroup root, final int attempt) {
        root.postDelayed(() -> {
            try {
                if (root.findViewWithTag(TAG) != null) return;
                if (tryAttach(activity, root, attempt)) {
                    root.postDelayed(() -> Diag.flush(root.getContext()), 800L);
                    return;
                }
            } catch (Throwable t) {
                XposedLog.e("注入设置失败（第 " + (attempt + 1) + " 次）", t);
                if (attempt + 1 < MAX_ATTEMPTS) schedule(activity, root, attempt + 1);
                return;
            }
            if (attempt + 1 < MAX_ATTEMPTS) {
                schedule(activity, root, attempt + 1);
            } else {
                XposedLog.w("重试 " + MAX_ATTEMPTS + " 次仍没找到可插入的位置，页面结构：" + describe(root));
                addFloatingButton(activity, root);
            }
        }, attempt == 0 ? 320L : 220L);
    }

    /** @return true 表示处理完了（注入成功或已存在）；false 表示页面还没铺好，再来一次。 */
    private static boolean tryAttach(Activity activity, ViewGroup root, int attempt) {
        if (root.findViewWithTag(TAG) != null) return true;

        View recycler = findRecycler(root);
        if (recycler == null) {
            XposedLog.d("第 " + (attempt + 1) + " 次：页面里还没有列表");
            return false;
        }

        // 首选：把页面列表搬进同一个滚动容器，模块配置紧接其后，
        // 整页只有一个滚动区，看起来就是原生设置项的延续
        View panel = SettingsPanel.create(activity);
        panel.setTag(TAG);
        if (mergeIntoScroll(activity, root, panel, recycler)) {
            return true;
        }

        // 结构还在变（列表刚 add、父容器还没量高），先等下一轮
        if (attempt + 1 < MAX_ATTEMPTS) {
            XposedLog.d("第 " + (attempt + 1) + " 次：列表还没准备好，稍后重试");
            return false;
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
    private static boolean mergeIntoScroll(Activity activity, ViewGroup root, View panel,
                                           View recycler) {
        try {
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
        if (!(recycler.getParent() instanceof ViewGroup)) return false;
        final ViewGroup parent = (ViewGroup) recycler.getParent();

        int index = parent.indexOfChild(recycler);
        int top = recycler.getTop();
        int limit = availableBottom(parent, recycler);
        int height = limit - top;
        if (height <= 0) {
            XposedLog.d("列表可用高度算不出来：parent=" + parent.getWidth() + "x"
                    + parent.getHeight() + " top=" + top + " limit=" + limit);
            return false;
        }

        // 列表自己的 LayoutParams 就是父容器认识的类型（FrameLayout / ConstraintLayout /
        // RelativeLayout 都一样）。直接把它交给 ScrollView 复用，横向约束、外边距、
        // 垂直定位规则全部原样保留，只改高度——不用给每种容器各写一套定位逻辑。
        ViewGroup.LayoutParams inherited = recycler.getLayoutParams();

        parent.removeView(recycler);
        stopNestedScroll(recycler);
        // 换了父容器，列表必须换成 LinearLayout 的 params，否则约束规则会被误用
        recycler.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        int insertAt = Math.max(0, Math.min(index, parent.getChildCount()));
        if (inherited != null) {
            inherited.height = height;
            scroll.setLayoutParams(inherited);
            parent.addView(scroll, insertAt);
        } else {
            parent.addView(scroll, insertAt, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height));
        }

        XposedLog.i("已自建滚动区承载配置，top=" + top + " height=" + height
                + "，父容器=" + parent.getClass().getSimpleName()
                + "，沿用=" + (inherited == null ? "默认" : inherited.getClass().getSimpleName()));
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
