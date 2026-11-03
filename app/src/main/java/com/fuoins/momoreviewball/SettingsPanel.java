package com.fuoins.momoreviewball;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块的手势配置面板。
 *
 * <p>正常情况下由 {@link SettingsInjector} 直接内联进墨墨「手势悬浮球」设置页，
 * 跟页面原有内容一起滚动，不需要再弹一层页面；只有内联失败时才退化成一个 Dialog。
 *
 * <p>动作名称沿用墨墨自己的颜色：认识绿、模糊橙、忘记红、熟知青、发音紫、返回灰。
 */
public final class SettingsPanel {

    private static final int NAVY = 0xFF2F3E6E;
    private static final int TEXT = 0xFF1F2430;
    private static final int SUB = 0xFF8A8F9C;

    private SettingsPanel() {
    }

    /** 动作名 → 墨墨自带配色（用户自定义过则以配置里的为准）。 */
    static int colorOf(String key) {
        return Config.defaultColorOf(key);
    }

    /** 生成可以塞进任意页面的一块配置区域。 */
    public static View create(final Activity activity) {
        final Config config = Config.get(activity);
        final List<Runnable> reloaders = new ArrayList<>();

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        // 左右留白对齐墨墨原生设置项，看起来是同一条列表的延续
        int padH = Util.dp(activity, 24);
        card.setPadding(padH, 0, padH, Util.dp(activity, 24));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardLp);

        card.addView(divider(activity));

        // 尺寸放在最前面：正好接在原生设置项「出现答案前显示悬浮球」下面，
        // 顶替掉原来那个「悬浮球尺寸」。
        card.addView(sizeRow(activity, config, reloaders));
        card.addView(hint(activity,
                "拖动时预览和复习页的球一起变，1.0 倍就是墨墨原生大小；预览用的是「单词出现前」配色。"));

        // 「熟知」不是默认就有的按钮，墨墨要在别处打开才会出现，这里给一条常驻提示，
        // 只在真的用到了「熟知」时显示。
        final TextView familiarNotice = notice(activity, "");
        final Runnable syncFamiliar = () -> {
            if (!config.usesFamiliar()) {
                familiarNotice.setVisibility(View.GONE);
                return;
            }
            familiarNotice.setVisibility(View.VISIBLE);
            familiarNotice.setText(config.autoFamiliar
                    ? "用到了「熟知」：答案出现后底栏会从「认识 / 模糊 / 忘记」变成四个，"
                        + "多出的「熟知」点一下就标；悬浮球滑「熟知」也走同一个入口，"
                        + "顶栏入口如果开着会优先点顶栏。"
                    : "用到了「熟知」：底栏保持三个按钮，悬浮球只会找墨墨自己的顶栏「熟知」；"
                        + "也可以手动长按底部「认识」上划到「标记熟知」。");
        };

        final TextView peekNotice = notice(activity,
                "「瞄一眼」走墨墨官方的按住预览：手指滑到位、还没松手时就把例句顶出来，"
                        + "一松手立刻恢复。模块不自己画提示层，只在答案出现前可用。");
        final Runnable syncPeek = () -> peekNotice.setVisibility(
                containsAction(config.before, Config.ACTION_PEEK) ? View.VISIBLE : View.GONE);

        final Runnable syncNotice = () -> {
            syncFamiliar.run();
            syncPeek.run();
        };

        card.addView(sectionTitle(activity, "单词出现前"));
        card.addView(hint(activity, "还没显示答案时生效（显示单词 / 瞄一眼 / 发音 / 返回）"));
        for (int i = 0; i < Config.GESTURE_LABELS.length; i++) {
            card.addView(gestureRow(activity, config, false, i, syncNotice, reloaders));
        }
        card.addView(peekNotice);

        card.addView(sectionTitle(activity, "单词出现后"));
        card.addView(hint(activity, "出现「认识 / 模糊 / 忘记」评分按钮后生效"));
        for (int i = 0; i < Config.GESTURE_LABELS.length; i++) {
            card.addView(gestureRow(activity, config, true, i, syncNotice, reloaders));
        }
        card.addView(familiarNotice);
        syncNotice.run();

        card.addView(sectionTitle(activity, "悬浮球"));
        card.addView(switchRow(activity, "锁定位置（长按也拖不动）", config.locked, checked -> {
            config.locked = checked;
            config.save(activity);
        }));
        card.addView(switchRow(activity, "启用模块手势", config.enabled, checked -> {
            config.enabled = checked;
            config.save(activity);
        }));
        card.addView(switchRow(activity, "底栏添加「熟知」按钮", config.autoFamiliar, checked -> {
            config.autoFamiliar = checked;
            config.save(activity);
            syncNotice.run();
        }));
        card.addView(hint(activity,
                "开启后底栏变成「熟知 / 认识 / 模糊 / 忘记」四个，和原版三个按钮同排；"
                        + "关掉则保持三个，悬浮球只尝试顶栏入口。"));

        card.addView(colorSection(activity, config, reloaders));

        Button reset = new Button(activity);
        reset.setText("恢复默认设置");
        reset.setAllCaps(false);
        reset.setTextColor(NAVY);
        reset.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = Util.dp(activity, 12);
        reset.setLayoutParams(resetLp);
        reset.setOnClickListener(v -> {
            config.reset();
            config.save(activity);
            for (Runnable reloader : reloaders) reloader.run();
            syncNotice.run();
            FloatingButtonHook.applyScale();
            ActionExecutor.toast(activity, "已恢复默认设置");
        });
        card.addView(reset);

        Button diag = new Button(activity);
        diag.setText("导出诊断日志");
        diag.setAllCaps(false);
        diag.setTextColor(0xFF8A8F9C);
        diag.setBackgroundColor(Color.TRANSPARENT);
        diag.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        diag.setOnClickListener(v -> {
            String text = Diag.build(activity);
            java.io.File file = Diag.write(activity, text);
            Diag.copyToClipboard(activity, text);
            if (file != null) {
                ActionExecutor.toast(activity, "已导出并复制到剪贴板\n" + file.getAbsolutePath());
            } else {
                ActionExecutor.toast(activity, "文件写入失败，日志已复制到剪贴板");
            }
        });
        card.addView(diag);

        // 日志本身不在这里展示，用的时候导出就行
        TextView logHint = new TextView(activity);
        logHint.setText("调试用。日志只在内存里滚动保留最近 600 条，满了自动丢最早的，"
                + "不会随使用时间一直变大；需要时点上面导出。");
        logHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logHint.setTextColor(0xFF9AA0AC);
        logHint.setLineSpacing(Util.dp(activity, 2), 1f);
        logHint.setPadding(0, Util.dp(activity, 2), 0, 0);
        card.addView(logHint);

        // 底部放模块自己的信息：捐赠、源码、版本号。
        // 放在原生「禁用」之前，整页还是同一条滚动流。
        card.addView(sectionTitle(activity, "关于"));
        card.addView(linkRow(activity, "捐赠支持", "zy520.de5.net/juanzeng",
                "https://zy520.de5.net/juanzeng/"));
        card.addView(linkRow(activity, "项目地址", "github.com/fuoins/momo-review-ball",
                "https://github.com/fuoins/momo-review-ball"));
        card.addView(infoRow(activity, "模块版本",
                BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）"));

        return card;
    }

    private static View linkRow(Activity activity, String title, String subtitle, String url) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(activity, 8);
        row.setLayoutParams(lp);
        int padV = Util.dp(activity, 6);
        row.setPadding(0, padV, 0, padV);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(activity);
        name.setText(title);
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        texts.addView(name);

        TextView desc = new TextView(activity);
        desc.setText(subtitle);
        desc.setTextColor(SUB);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        desc.setPadding(0, Util.dp(activity, 2), 0, 0);
        texts.addView(desc);
        row.addView(texts);

        TextView arrow = new TextView(activity);
        arrow.setText("›");
        arrow.setTextColor(0xFFB9BEC9);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        arrow.setPadding(Util.dp(activity, 6), 0, 0, 0);
        row.addView(arrow);

        row.setOnClickListener(v -> openUrl(activity, url));
        return row;
    }

    private static View infoRow(Activity activity, String title, String value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(activity, 14);
        row.setLayoutParams(lp);

        TextView name = new TextView(activity);
        name.setText(title);
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView valueText = new TextView(activity);
        valueText.setText(value);
        valueText.setTextColor(SUB);
        valueText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(valueText);
        return row;
    }

    private static void openUrl(Activity activity, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            ActionExecutor.toast(activity, "没有可用的浏览器：" + url);
        }
    }

    /** 自定义大小：一行标题 + 百分比，下面滑杆，再下面实时预览的球。 */
    private static View sizeRow(Activity activity, Config config, List<Runnable> reloaders) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = Util.dp(activity, 18);
        boxLp.bottomMargin = Util.dp(activity, 2);
        box.setLayoutParams(boxLp);

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(activity);
        label.setText("自定义大小");
        label.setTextColor(TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(label);

        final TextView value = new TextView(activity);
        value.setTextColor(NAVY);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.getPaint().setFakeBoldText(true);
        head.addView(value);
        box.addView(head);

        SeekBar bar = new SeekBar(activity);
        bar.setMax(Math.round((Config.SCALE_MAX - Config.SCALE_MIN) * 100f));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = Util.dp(activity, 4);
        box.addView(bar, barLp);

        // 滑杆下面就是预览，拖到哪儿球就多大
        final BallPreview preview = new BallPreview(activity, config);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewLp.topMargin = Util.dp(activity, 6);
        box.addView(preview, previewLp);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float scale = Config.SCALE_MIN + progress / 100f;
                value.setText(Math.round(scale * 100) + "%");
                preview.setScale(scale);
                if (!fromUser) return;
                config.scale = scale;
                // 复习页的球如果还在窗口里，跟着一起变
                FloatingButtonHook.applyScale();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                config.save(activity);
            }
        });

        // 初始状态 + 「恢复默认」时回到原位
        Runnable reload = () -> {
            int progress = Math.round((config.scale - Config.SCALE_MIN) * 100f);
            bar.setProgress(Config.clamp(progress, 0, bar.getMax()));
            value.setText(Math.round(config.scale * 100) + "%");
            preview.setScale(config.scale);
            // 尺寸没变时 setScale 会直接返回，但动作颜色可能刚改过，仍要重画一次
            preview.invalidate();
        };
        reload.run();
        reloaders.add(reload);
        return box;
    }

    /**
     * 动作颜色：每个动作一个色块，点开取色图，球面四向色带和中心圆点跟着变。
     *
     * <p>颜色存在 {@link Config#colors} 里，没改过的动作仍用墨墨自带配色，
     * 所以升级模块不会把默认配色写死成一份快照。
     */
    private static View colorSection(final Activity activity, final Config config,
                                     List<Runnable> reloaders) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = Util.dp(activity, 20);
        box.setLayoutParams(boxLp);

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(activity);
        title.setText("动作颜色");
        title.setTextColor(NAVY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.getPaint().setFakeBoldText(true);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);

        TextView resetAll = new TextView(activity);
        resetAll.setText("全部恢复默认");
        resetAll.setTextColor(NAVY);
        resetAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        resetAll.setPadding(Util.dp(activity, 8), Util.dp(activity, 4), 0, Util.dp(activity, 4));
        head.addView(resetAll);
        box.addView(head);

        box.addView(hint(activity,
                "点色块打开取色图：球面的四向色带和中心圆点会一起变色，取色器里也能直接输入 #RRGGBB。"));

        final List<Runnable> refreshers = new ArrayList<>();
        LinearLayout line = null;
        for (int i = 0; i < Config.ACTION_KEYS.length; i++) {
            if (i % 4 == 0) {
                line = new LinearLayout(activity);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lineLp.topMargin = Util.dp(activity, i == 0 ? 6 : 2);
                box.addView(line, lineLp);
            }
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            line.addView(colorItem(activity, config, Config.ACTION_KEYS[i], refreshers, reloaders), itemLp);
        }

        // 改色后除了色块，下拉框里的动作文字颜色也要跟着变，所以顺带跑一遍
        // 其它配置项的 reload；用重入标记挡住 refreshAll 自己。
        final boolean[] refreshing = {false};
        final Runnable refreshAll = () -> {
            if (refreshing[0]) return;
            refreshing[0] = true;
            try {
                for (Runnable refresh : refreshers) refresh.run();
                for (Runnable reload : new ArrayList<>(reloaders)) reload.run();
            } finally {
                refreshing[0] = false;
            }
        };
        reloaders.add(refreshAll);
        resetAll.setOnClickListener(v -> {
            config.resetColors();
            config.save(activity);
            refreshAll.run();
            ActionExecutor.toast(activity, "已恢复默认配色");
        });
        return box;
    }

    /** 单个动作的色块 + 名称。 */
    private static View colorItem(final Activity activity, final Config config,
                                  final String key, List<Runnable> refreshers,
                                  List<Runnable> reloaders) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(0, Util.dp(activity, 8), 0, Util.dp(activity, 4));

        final View dot = new View(activity);
        dot.setLayoutParams(new LinearLayout.LayoutParams(
                Util.dp(activity, 40), Util.dp(activity, 40)));
        item.addView(dot);

        TextView name = new TextView(activity);
        name.setText(shortActionLabel(key));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        name.setTextColor(SUB);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = Util.dp(activity, 5);
        item.addView(name, nameLp);

        final Runnable refresh = () -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(config.colorOf(key));
            bg.setStroke(Math.max(1, Util.dp(activity, 1.4f)), 0x33000000);
            dot.setBackground(bg);
        };
        refreshers.add(refresh);
        refresh.run();

        item.setOnClickListener(v -> ColorPickerDialog.show(activity, key, config.colorOf(key),
                color -> {
                    config.setColor(key, color);
                    config.save(activity);
                    // reloaders 里已经有 colorSection 自己的 refreshAll，
                    // 它会重画所有色块并把下拉文字颜色一起刷新
                    for (Runnable reload : new ArrayList<>(reloaders)) reload.run();
                }));
        return item;
    }

    /** 色块下面用短名，避免「返回上一个单词」在窄格里被截成省略号。 */
    private static String shortActionLabel(String key) {
        switch (key) {
            case Config.ACTION_KNOW:
                return "认识";
            case Config.ACTION_VAGUE:
                return "模糊";
            case Config.ACTION_FORGET:
                return "忘记";
            case Config.ACTION_FAMILIAR:
                return "熟知";
            case Config.ACTION_PRONOUNCE:
                return "发音";
            case Config.ACTION_SHOW:
                return "显示";
            case Config.ACTION_PEEK:
                return "瞄一眼";
            case Config.ACTION_PREV:
                return "返回";
            default:
                return "无操作";
        }
    }

    /** 深色文字看白底、浅色文字看彩色，保证下拉项在自定义浅色下仍可读。 */
    static int readable(int color) {
        double lum = (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255d;
        return lum > 0.78 ? TEXT : color;
    }

    /** 和上方原生列表之间的一条细线，替代原来的卡片边界。 */
    private static View divider(Activity activity) {
        View line = new View(activity);
        line.setBackgroundColor(0xFFEDEFF3);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Util.dp(activity, 0.7f)));
        lp.topMargin = Util.dp(activity, 10);
        lp.bottomMargin = Util.dp(activity, 2);
        line.setLayoutParams(lp);
        return line;
    }

    /** 内联失败时的兜底：弹窗显示同一块内容。 */
    public static void show(final Activity activity) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(create(activity), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(
                activity, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("复习手势悬浮球")
                .setView(scroll)
                .setPositiveButton("完成", null)
                .create();
        dialog.show();
        try {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
        }
    }

    private static TextView sectionTitle(Activity activity, String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(NAVY);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(activity, 24);
        lp.bottomMargin = Util.dp(activity, 2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static TextView hint(Activity activity, String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(SUB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Util.dp(activity, 6);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 带浅色底的提示条，比普通灰字更容易被看到。 */
    private static TextView notice(Activity activity, String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(0xFFB26A00);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setLineSpacing(Util.dp(activity, 2), 1f);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFF6E8);
        bg.setCornerRadius(Util.dp(activity, 10));
        tv.setBackground(bg);
        int pad = Util.dp(activity, 12);
        tv.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(activity, 14);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static boolean usesFamiliar(Config config) {
        return config.usesFamiliar();
    }

    private static boolean containsAction(String[] actions, String key) {
        if (actions == null) return false;
        for (String action : actions) {
            if (key.equals(action)) return true;
        }
        return false;
    }

    private static View gestureRow(final Activity activity, final Config config,
                                   final boolean after, final int index,
                                   final Runnable onChanged, List<Runnable> reloaders) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Util.dp(activity, 14);
        row.setLayoutParams(rowLp);

        TextView label = new TextView(activity);
        label.setText(Config.GESTURE_LABELS[index]);
        label.setTextColor(TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        final String[] options = Config.actionsFor(after);
        Spinner spinner = new Spinner(activity, Spinner.MODE_DROPDOWN);
        ActionAdapter adapter = new ActionAdapter(activity, options, config);
        spinner.setAdapter(adapter);
        spinner.setSelection(Config.indexOf(options, config.actionFor(after, index)), false);
        spinner.setLayoutParams(new LinearLayout.LayoutParams(
                Util.dp(activity, 158), ViewGroup.LayoutParams.WRAP_CONTENT));

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String key = options[position];
                String[] target = after ? config.after : config.before;
                if (TextUtils.equals(target[index], key)) return;
                target[index] = key;
                config.save(activity);
                if (onChanged != null) onChanged.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(spinner);

        reloaders.add(() -> {
            adapter.notifyDataSetChanged();
            spinner.setSelection(
                    Config.indexOf(options, config.actionFor(after, index)), false);
        });
        return row;
    }

    private static View switchRow(final Activity activity, String title, boolean checked,
                                  final SwitchCallback callback) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(activity, 16);
        row.setLayoutParams(lp);

        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextColor(TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        Switch toggle = new Switch(activity);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> callback.onChanged(isChecked));
        row.addView(toggle);

        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        return row;
    }

    /** 下拉选项按动作上色，和墨墨自带的配色一致。 */
    private static final class ActionAdapter extends ArrayAdapter<String> {

        private final String[] keys;
        private final Config config;

        ActionAdapter(Activity activity, String[] keys, Config config) {
            super(activity, android.R.layout.simple_spinner_item, labels(keys));
            this.keys = keys;
            this.config = config;
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        private static String[] labels(String[] keys) {
            String[] out = new String[keys.length];
            for (int i = 0; i < keys.length; i++) out[i] = Config.labelOf(keys[i]);
            return out;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) super.getView(position, convertView, parent);
            tv.setTextColor(safeColor(position));
            return tv;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
            tv.setTextColor(safeColor(position));
            return tv;
        }

        private int safeColor(int position) {
            if (position < 0 || position >= keys.length) return TEXT;
            return readable(config.colorOf(keys[position]));
        }
    }

    private interface SwitchCallback {
        void onChanged(boolean checked);
    }
}
