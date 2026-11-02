package com.fuoins.momoreviewball;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
 * 模块设置面板。不做独立 App 界面，直接以 Dialog 形式开在墨墨的「手势悬浮球」设置页里。
 */
public final class SettingsPanel {

    private static final int NAVY = 0xFF2F3E6E;
    private static final int ORANGE = 0xFFF2A33C;
    private static final int TEXT = 0xFF1F2430;
    private static final int SUB = 0xFF8A8F9C;

    private SettingsPanel() {
    }

    public static void show(final Activity activity) {
        final Config config = Config.get(activity);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Util.dp(activity, 20);
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(sectionTitle(activity, "单词出现前"));
        box.addView(hint(activity, "还没点开答案、页面提示「请回忆」时生效"));
        for (int i = 0; i < Config.GESTURE_LABELS.length; i++) {
            box.addView(gestureRow(activity, config, false, i));
        }

        box.addView(sectionTitle(activity, "单词出现后"));
        box.addView(hint(activity, "出现「认识 / 模糊 / 忘记」评分按钮后生效"));
        for (int i = 0; i < Config.GESTURE_LABELS.length; i++) {
            box.addView(gestureRow(activity, config, true, i));
        }

        box.addView(sectionTitle(activity, "悬浮球"));
        box.addView(hint(activity, "拖动、边界吸附、按压动画都沿用墨墨自带的，模块只改手势对应的动作"));
        box.addView(switchRow(activity, "锁定位置（长按也拖不动）", config.locked, checked -> {
            config.locked = checked;
            config.save(activity);
        }));
        box.addView(switchRow(activity, "操作振动", config.haptic, checked -> {
            config.haptic = checked;
            config.save(activity);
        }));
        box.addView(switchRow(activity, "启用模块手势", config.enabled, checked -> {
            config.enabled = checked;
            config.save(activity);
        }));

        Button reset = new Button(activity);
        reset.setText("恢复默认手势");
        reset.setAllCaps(false);
        reset.setTextColor(NAVY);
        reset.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = Util.dp(activity, 8);
        reset.setLayoutParams(resetLp);
        reset.setOnClickListener(v -> {
            config.reset();
            config.save(activity);
            ActionExecutor.toast(activity, "已恢复默认设置");
        });
        box.addView(reset);

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
        lp.topMargin = Util.dp(activity, 14);
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
        rowLp.topMargin = Util.dp(activity, 4);
        row.setLayoutParams(rowLp);

        TextView label = new TextView(activity);
        label.setText(Config.GESTURE_LABELS[index]);
        label.setTextColor(TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelLp);
        row.addView(label);

        Spinner spinner = new Spinner(activity, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, Config.ACTION_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        String current = config.actionFor(after, index);
        spinner.setSelection(indexOf(current), false);

        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(
                Util.dp(activity, 150), ViewGroup.LayoutParams.WRAP_CONTENT);
        spinner.setLayoutParams(spinnerLp);

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
        lp.topMargin = Util.dp(activity, 6);
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

        // 点击整行都能切换
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        return row;
    }

    private static int indexOf(String key) {
        for (int i = 0; i < Config.ACTION_KEYS.length; i++) {
            if (Config.ACTION_KEYS[i].equals(key)) return i;
        }
        return 0;
    }

    private interface SwitchCallback {
        void onChanged(boolean checked);
    }
}
