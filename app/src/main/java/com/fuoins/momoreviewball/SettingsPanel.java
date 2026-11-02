package com.fuoins.momoreviewball;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
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
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 模块的手势配置面板。
 *
 * <p>正常情况下由 {@link SettingsInjector} 直接内联进墨墨「手势悬浮球」设置页，
 * 跟页面原有内容一起滚动，不需要再弹一层页面；只有内联失败时才退化成一个 Dialog。
 *
 * <p>动作名称沿用墨墨自己的颜色：认识绿、模糊橙、忘记红、熟知青、发音紫、瞄一眼蓝、返回灰。
 */
public final class SettingsPanel {

    private static final int NAVY = 0xFF2F3E6E;
    private static final int TEXT = 0xFF1F2430;
    private static final int SUB = 0xFF8A8F9C;

    private SettingsPanel() {
    }

    /** 动作名 → 墨墨自带配色。 */
    static int colorOf(String key) {
        switch (key) {
            case Config.ACTION_KNOW:
                return 0xFF4CAF50;   // 认识 · 绿
            case Config.ACTION_VAGUE:
                return 0xFFF2A33C;   // 模糊 · 橙
            case Config.ACTION_FORGET:
                return 0xFFE53935;   // 忘记 · 红
            case Config.ACTION_FAMILIAR:
                return 0xFF00897B;   // 熟知 · 青
            case Config.ACTION_PRONOUNCE:
                return 0xFF8E44AD;   // 发音 · 紫
            case Config.ACTION_PEEK:
                return 0xFF2196F3;   // 瞄一眼 · 蓝
            case Config.ACTION_PREV:
                return 0xFF9E9E9E;   // 返回上一个单词 · 灰
            case Config.ACTION_SHOW:
                return 0xFF546E7A;   // 显示单词 · 蓝灰
            default:
                return 0xFFBDBDBD;   // 无操作
        }
    }

    private static int[] colors() {
        int[] out = new int[Config.ACTION_KEYS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = colorOf(Config.ACTION_KEYS[i]);
        }
        return out;
    }

    /** 生成可以塞进任意页面的一块配置区域。 */
    public static View create(final Activity activity) {
        final Config config = Config.get(activity);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        // 左右留白对齐墨墨原生设置项，看起来是同一条列表的延续
        int padH = Util.dp(activity, 24);
        card.setPadding(padH, 0, padH, Util.dp(activity, 24));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardLp);

        card.addView(divider(activity));

        card.addView(sectionTitle(activity, "单词出现前"));
        card.addView(hint(activity, "还没显示答案时生效"));
        for (int i = 0; i < Config.GESTURE_LABELS.length; i++) {
            card.addView(gestureRow(activity, config, false, i));
        }

        card.addView(sectionTitle(activity, "单词出现后"));
        card.addView(hint(activity, "出现「认识 / 模糊 / 忘记」评分按钮后生效"));
        for (int i = 0; i < Config.GESTURE_LABELS.length; i++) {
            card.addView(gestureRow(activity, config, true, i));
        }

        card.addView(sectionTitle(activity, "悬浮球"));
        card.addView(switchRow(activity, "锁定位置（长按也拖不动）", config.locked, checked -> {
            config.locked = checked;
            config.save(activity);
        }));
        card.addView(switchRow(activity, "操作振动", config.haptic, checked -> {
            config.haptic = checked;
            config.save(activity);
        }));
        card.addView(switchRow(activity, "启用模块手势", config.enabled, checked -> {
            config.enabled = checked;
            config.save(activity);
        }));

        Button reset = new Button(activity);
        reset.setText("恢复默认手势");
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

        // 最近几行事件：不用导出也能看到模块到底有没有接管到球
        TextView recent = new TextView(activity);
        recent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        recent.setTextColor(0xFF9AA0AC);
        recent.setPadding(0, Util.dp(activity, 6), 0, 0);
        recent.setText(Diag.recent(3));
        recent.setOnClickListener(v -> recent.setText(Diag.recent(14)));
        card.addView(recent);

        return card;
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

    private static View gestureRow(final Activity activity, final Config config,
                                   final boolean after, final int index) {
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

        Spinner spinner = new Spinner(activity, Spinner.MODE_DROPDOWN);
        ActionAdapter adapter = new ActionAdapter(activity, colors());
        spinner.setAdapter(adapter);
        spinner.setSelection(indexOf(config.actionFor(after, index)), false);
        spinner.setLayoutParams(new LinearLayout.LayoutParams(
                Util.dp(activity, 158), ViewGroup.LayoutParams.WRAP_CONTENT));

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String key = Config.ACTION_KEYS[position];
                String[] target = after ? config.after : config.before;
                if (TextUtils.equals(target[index], key)) return;
                target[index] = key;
                config.save(activity);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(spinner);
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

    private static int indexOf(String key) {
        for (int i = 0; i < Config.ACTION_KEYS.length; i++) {
            if (Config.ACTION_KEYS[i].equals(key)) return i;
        }
        return 0;
    }

    /** 下拉选项按动作上色，和墨墨自带的配色一致。 */
    private static final class ActionAdapter extends ArrayAdapter<String> {

        private final int[] colors;

        ActionAdapter(Activity activity, int[] colors) {
            super(activity, android.R.layout.simple_spinner_item, Config.ACTION_LABELS);
            this.colors = colors;
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
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
            if (position < 0 || position >= colors.length) return TEXT;
            return colors[position];
        }
    }

    private interface SwitchCallback {
        void onChanged(boolean checked);
    }
}
