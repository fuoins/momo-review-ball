package com.fuoins.momoreviewball;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 内存里的环形日志 + 一键导出。
 *
 * <p>模块跑在墨墨进程里，用户不方便开电脑抓 logcat，所以把关键事件都记在内存里，
 * 设置页底部点一下就能导出成文本，顺便复制到剪贴板。
 */
final class Diag {

    private static final int MAX_LINES = 600;
    private static final int MAX_NODES = 220;

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat CLOCK =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private static final SimpleDateFormat FULL =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private Diag() {
    }

    static void add(String level, String msg) {
        String now;
        synchronized (CLOCK) {
            now = CLOCK.format(new Date());
        }
        synchronized (LINES) {
            if (LINES.size() >= MAX_LINES) LINES.removeFirst();
            LINES.addLast(now + " " + level + " " + msg);
        }
    }

    static void add(String level, String msg, Throwable t) {
        add(level, msg + " | " + t);
        if (t != null) {
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < st.length && i < 6; i++) {
                add(level, "    at " + st[i]);
            }
        }
    }

    /** 生成完整诊断文本：设备信息 + 事件日志 + 当前页面视图树。 */
    static String build(Activity activity) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("=== 墨墨复习手势悬浮球 · 诊断 ===\n");
        synchronized (FULL) {
            sb.append("导出时间 : ").append(FULL.format(new Date())).append('\n');
        }
        sb.append("模块版本 : ").append(moduleVersion(activity)).append('\n');
        sb.append("设备     : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统     : Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("屏幕     : ").append(screenInfo(activity)).append('\n');
        sb.append("当前页面 : ").append(activity == null ? "?" : activity.getClass().getName()).append('\n');
        sb.append("配置     : ").append(configInfo(activity)).append('\n');
        sb.append('\n');

        sb.append("--- 事件日志（最近 ").append(MAX_LINES).append(" 条）---\n");
        synchronized (LINES) {
            if (LINES.isEmpty()) {
                sb.append("(空：模块可能没有在墨墨进程里加载)\n");
            } else {
                for (String line : LINES) sb.append(line).append('\n');
            }
        }
        sb.append('\n');

        sb.append("--- 悬浮球控件 ---\n");
        appendBallInfo(activity, sb);
        sb.append('\n');

        sb.append("--- 当前页面视图树 ---\n");
        if (activity != null) {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null) {
                dumpView(decor, sb, 0, new int[]{MAX_NODES});
            } else {
                sb.append("(拿不到 DecorView)\n");
            }
        } else {
            sb.append("(没有前台 Activity)\n");
        }
        return sb.toString();
    }

    /** 写到应用外部私有目录，不需要任何存储权限。 */
    static File write(Activity activity, String text) {
        if (activity == null) return null;
        String name;
        synchronized (STAMP) {
            name = "momo_diag_" + STAMP.format(new Date()) + ".txt";
        }
        File out = writeTo(activity, text, name);
        if (out != null) prune(activity, 10);
        return out;
    }

    /** 导出的文件只留最近 10 份，不然每点一次就多一个，时间久了会堆一堆。 */
    private static void prune(Context ctx, int keep) {
        try {
            File dir = diagnoseDir(ctx);
            if (dir == null) return;
            File[] files = dir.listFiles((d, n) ->
                    n.startsWith("momo_diag_") && n.endsWith(".txt"));
            if (files == null || files.length <= keep) return;
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < files.length - keep; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把当前事件日志刷到固定的 latest.txt。
     *
     * <p>设置页注入失败时用户看不到导出按钮，但只要是复习页，这个文件就一直是最新的，
     * 可以直接从文件管理器取。
     */
    static void flush(Context ctx) {
        if (ctx == null) return;
        try {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("=== MoMoBall 事件日志 ===\n");
            synchronized (LINES) {
                for (String line : LINES) sb.append(line).append('\n');
            }
            writeTo(ctx, sb.toString(), "latest.txt");
        } catch (Throwable ignored) {
        }
    }

    private static File diagnoseDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        if (base == null) return null;
        File dir = new File(base, "diagnose");
        if (!dir.exists() && !dir.mkdirs()) return null;
        return dir;
    }

    private static File writeTo(Context ctx, String text, String name) {
        try {
            File dir = diagnoseDir(ctx);
            if (dir == null) return null;
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    static void copyToClipboard(Context context, String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("MoMoBall 诊断", text));
        } catch (Throwable ignored) {
        }
    }

    private static String moduleVersion(Context ctx) {
        // 模块是普通应用、代码却跑在墨墨进程里，跨包查询会被包可见性挡住，
        // 直接用编译期常量最稳
        try {
            return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String screenInfo(Context ctx) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return "?";
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            return dm.widthPixels + "x" + dm.heightPixels
                    + "  density=" + dm.density + "  dpi=" + dm.densityDpi;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String configInfo(Activity activity) {
        if (activity == null) return "?";
        try {
            Config c = Config.get(activity);
            return "enabled=" + c.enabled + " locked=" + c.locked
                    + " size=" + Math.round(c.scale * 100) + "%"
                    + " before=" + TextUtils.join("/", c.before)
                    + " after=" + TextUtils.join("/", c.after);
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    private static void appendBallInfo(Activity activity, StringBuilder sb) {
        if (activity == null) {
            sb.append("(没有前台 Activity)\n");
            return;
        }
        try {
            View decor = activity.getWindow().getDecorView();
            int id = activity.getResources()
                    .getIdentifier("rev_floating_btn", "id", activity.getPackageName());
            if (id == 0) {
                sb.append("资源里没有 rev_floating_btn\n");
                return;
            }
            View ball = decor.findViewById(id);
            if (ball == null) {
                sb.append("当前页面没有球（不在复习页）\n");
                return;
            }
            sb.append("类名   : ").append(ball.getClass().getName()).append('\n');
            sb.append("尺寸   : ").append(ball.getWidth()).append('x').append(ball.getHeight()).append('\n');
            sb.append("位置   : (").append(ball.getLeft()).append(',').append(ball.getTop()).append(")\n");
            sb.append("可见   : ").append(ball.getVisibility() == View.VISIBLE).append('\n');
            sb.append("已挂载 : ").append(ball.isAttachedToWindow()).append('\n');
            sb.append("子View : ").append(ball instanceof ViewGroup
                    ? ((ViewGroup) ball).getChildCount() : -1).append('\n');
            sb.append("背景   : ").append(ball.getBackground()).append('\n');
            sb.append("透明层 : ").append(ball.getOverlay()).append('\n');
            sb.append("触摸   : ").append(ball.hasOnClickListeners()).append('\n');
            if (ball instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) ball;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    sb.append("  子[").append(i).append("] ")
                            .append(c.getClass().getSimpleName())
                            .append(' ').append(c.getWidth()).append('x').append(c.getHeight())
                            .append(" @(").append(c.getLeft()).append(',').append(c.getTop()).append(')')
                            .append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("读取球信息失败: ").append(t).append('\n');
        }
    }

    private static void dumpView(View v, StringBuilder sb, int depth, int[] budget) {
        if (budget[0] <= 0) return;
        budget[0]--;
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName());
        try {
            if (v.getId() != View.NO_ID) {
                sb.append('#').append(v.getResources().getResourceEntryName(v.getId()));
            }
        } catch (Throwable ignored) {
        }
        sb.append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                .append(" @(").append(v.getLeft()).append(',').append(v.getTop()).append(')');
        if (v.getVisibility() != View.VISIBLE) {
            sb.append(v.getVisibility() == View.GONE ? " [GONE]" : " [INVISIBLE]");
        }
        if (v instanceof TextView) {
            CharSequence text = ((TextView) v).getText();
            if (!TextUtils.isEmpty(text)) {
                String s = text.toString().replace('\n', ' ');
                sb.append(" \"").append(s.length() > 30 ? s.substring(0, 30) + "…" : s).append('"');
            }
        }
        sb.append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpView(group.getChildAt(i), sb, depth + 1, budget);
            }
        }
    }
}
