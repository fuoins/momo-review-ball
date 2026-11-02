package com.fuoins.momoreviewball;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 模块设置页，纯原生控件，避免额外依赖。 */
public class SettingsActivity extends Activity {

    private static final int BG = 0xFFF4F5F8;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF1B1D24;
    private static final int MUTED = 0xFF7A7F8C;
    private static final int NAVY = 0xFF2F3E6E;
    private static final int AMBER = 0xFFF2A33C;
    private static final int LINE = 0xFFEDEFF4;

    private Config config;
    private int stateIndex = 0;
    private boolean binding;

    private final Spinner[] spinners = new Spinner[5];
    private TextView tabBefore;
    private TextView tabAfter;
    private TextView sizeValue;
    private TextView alphaValue;
    private TextView xValue;
    private TextView yValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = Store.load(this);
        setupWindow();
        setContentView(buildContent());
    }

    private void setupWindow() {
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        View decor = window.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("墨墨复习悬浮球", 26, TEXT, true);
        root.addView(title);
        TextView subtitle = text("LSPosed 模块 · 给复习页加一个顺手的悬浮球", 13, MUTED, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        root.addView(subtitle, subLp);

        root.addView(buildStatusCard(), marginTop(dp(18)));
        root.addView(sectionTitle("悬浮球"), marginTop(dp(24)));
        root.addView(buildBallCard(), marginTop(dp(10)));
        root.addView(sectionTitle("手势动作"), marginTop(dp(24)));
        root.addView(buildGestureCard(), marginTop(dp(10)));
        root.addView(sectionTitle("使用说明"), marginTop(dp(24)));
        root.addView(buildHelpCard(), marginTop(dp(10)));

        TextView footer = text("v1.0.0 · 适用于墨墨背单词 5.5.25+\n仅供个人学习使用", 11, 0xFF9AA0AC, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = dp(26);
        root.addView(footer, footerLp);
        return scroll;
    }

    private View buildStatusCard() {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text("启用悬浮球", 16, TEXT, true));
        TextView hint = text("仅在墨墨背单词的复习页显示", 12, MUTED, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(3);
        left.addView(hint, hintLp);
        row.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(config.enabled);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.enabled = isChecked;
            save();
        });
        row.addView(toggle);
        card.addView(row);

        card.addView(divider(marginTop(dp(14))));

        TextView scope = text("作用范围  com.maimemo.android.momo  ·  revision.ReviewActivity", 11, MUTED, false);
        scope.setPadding(0, dp(10), 0, 0);
        card.addView(scope);

        Button open = new Button(this);
        open.setText("打开墨墨背单词");
        open.setAllCaps(false);
        open.setTextSize(14);
        open.setTextColor(NAVY);
        open.setBackground(bg(0xFFF3F5FA, 12));
        open.setPadding(dp(14), 0, dp(14), 0);
        open.setOnClickListener(v -> openMomo());
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        openLp.topMargin = dp(14);
        card.addView(open, openLp);

        return card;
    }

    private View buildBallCard() {
        LinearLayout card = card();

        sizeValue = text(config.sizeDp + " dp", 13, NAVY, true);
        card.addView(sliderRow("尺寸", sizeValue, 40, 72, config.sizeDp, (progress, fromUser) -> {
            int value = progress;
            config.sizeDp = value;
            sizeValue.setText(value + " dp");
            if (!fromUser) save();
        }, () -> save()));

        alphaValue = text(config.alpha + "%", 13, NAVY, true);
        card.addView(sliderRow("不透明度", alphaValue, 25, 100, config.alpha, (progress, fromUser) -> {
            config.alpha = progress;
            alphaValue.setText(progress + "%");
            if (!fromUser) save();
        }, () -> save()));

        card.addView(divider(marginTop(dp(6))));

        card.addView(switchRow("固定位置", "锁定后拖动无效，长按可切换", config.locked,
                (buttonView, checked) -> {
                    config.locked = checked;
                    save();
                }));

        card.addView(switchRow("长按锁定", "长按悬浮球 0.5 秒固定/解锁位置", config.longPressLock,
                (buttonView, checked) -> {
                    config.longPressLock = checked;
                    save();
                }));

        card.addView(switchRow("操作振动", "执行手势时给出轻微振动反馈", config.haptic,
                (buttonView, checked) -> {
                    config.haptic = checked;
                    save();
                }));

        card.addView(divider(marginTop(dp(8))));

        xValue = text(Math.round(config.xRatio * 100) + "%", 13, NAVY, true);
        card.addView(sliderRow("水平位置", xValue, 0, 100, Math.round(config.xRatio * 100),
                (progress, fromUser) -> {
                    config.xRatio = progress / 100f;
                    xValue.setText(progress + "%");
                    if (!fromUser) save();
                }, () -> save()));

        yValue = text(Math.round(config.yRatio * 100) + "%", 13, NAVY, true);
        card.addView(sliderRow("垂直位置", yValue, 0, 100, Math.round(config.yRatio * 100),
                (progress, fromUser) -> {
                    config.yRatio = progress / 100f;
                    yValue.setText(progress + "%");
                    if (!fromUser) save();
                }, () -> save()));

        Button reset = new Button(this);
        reset.setText("重置到右侧默认位置");
        reset.setAllCaps(false);
        reset.setTextSize(13);
        reset.setTextColor(MUTED);
        reset.setBackground(bg(0xFFF3F5FA, 12));
        reset.setOnClickListener(v -> {
            config.xRatio = 0.92f;
            config.yRatio = 0.62f;
            config.locked = false;
            save();
            toast("位置已重置，回到复习页生效");
            recreate();
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        resetLp.topMargin = dp(12);
        card.addView(reset, resetLp);

        return card;
    }

    private View buildGestureCard() {
        LinearLayout card = card();

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(bg(0xFFF1F3F8, 14));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));

        tabBefore = tab("单词出现前", true);
        tabAfter = tab("单词出现后", false);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabs.addView(tabBefore, tabLp);
        tabs.addView(tabAfter, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tabBefore.setOnClickListener(v -> switchTab(0));
        tabAfter.setOnClickListener(v -> switchTab(1));
        card.addView(tabs);

        TextView hint = text("同一手势在答案出现前后可以绑定不同动作", 11, MUTED, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(10);
        card.addView(hint, hintLp);

        for (int i = 0; i < 5; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));

            TextView label = text(Config.GESTURE_LABELS[i], 15, TEXT, false);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Spinner spinner = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, Config.ACTION_LABELS);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            spinner.setSelection(indexOf(Config.ACTION_KEYS,
                    (stateIndex == 0 ? config.before : config.after)[i]), false);
            final int index = i;
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (binding) return;
                    if (stateIndex == 0) {
                        config.before[index] = Config.ACTION_KEYS[position];
                    } else {
                        config.after[index] = Config.ACTION_KEYS[position];
                    }
                    save();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            spinners[i] = spinner;
            LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(
                    dp(176), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(spinner, spinnerLp);
            card.addView(row);
        }

        card.addView(divider(marginTop(dp(8))));
        TextView tip = text("长按悬浮球可固定或解锁位置；拖动可以自由换位。", 11, MUTED, false);
        tip.setPadding(0, dp(10), 0, 0);
        card.addView(tip);

        return card;
    }

    private View buildHelpCard() {
        LinearLayout card = card();
        String text = "1. 在 LSPosed 中启用本模块，作用域勾选「墨墨背单词」。\n"
                + "2. 强制停止墨墨背单词后重新打开，进入任意复习页。\n"
                + "3. 单击 / 上滑 / 下滑 / 左滑 / 右滑悬浮球执行动作；拖动换位置。\n"
                + "4. 修改设置后约 1 秒生效，无需重启手机。\n\n"
                + "提示：动作是通过复习页上的按钮完成的。如果当前页面还没有「认识 / 忘记」等按钮，"
                + "对应的动作会提示不可用；「瞄一眼」在没有可见按钮时，会用气泡短暂显示当前单词。";
        TextView body = text(text, 12, 0xFF5E6473, false);
        body.setLineSpacing(dp(3), 1f);
        card.addView(body);
        return card;
    }

    private void switchTab(int index) {
        if (stateIndex == index) return;
        stateIndex = index;
        styleTab(tabBefore, index == 0);
        styleTab(tabAfter, index == 1);
        refreshSpinners();
    }

    private void refreshSpinners() {
        binding = true;
        String[] array = stateIndex == 0 ? config.before : config.after;
        for (int i = 0; i < spinners.length; i++) {
            spinners[i].setSelection(indexOf(Config.ACTION_KEYS, array[i]), false);
        }
        binding = false;
    }

    private TextView tab(String label, boolean selected) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(9), 0, dp(9));
        styleTab(view, selected);
        return view;
    }

    private void styleTab(TextView view, boolean selected) {
        view.setTextColor(selected ? Color.WHITE : MUTED);
        view.setTypeface(null, selected ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        view.setBackground(bg(selected ? NAVY : 0x00000000, 12));
    }

    private interface SliderCallback {
        void onValue(int value, boolean fromUser);
    }

    private View sliderRow(String title, TextView valueView, int min, int max, int current,
                           SliderCallback callback, Runnable onStop) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, dp(8), 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text(title, 15, TEXT, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(valueView);
        wrapper.addView(header);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Config.clamp(current - min, 0, max - min));
        bar.setPadding(0, dp(4), 0, 0);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                callback.onValue(progress + min, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                onStop.run();
            }
        });
        wrapper.addView(bar);
        return wrapper;
    }

    private View switchRow(String title, String subtitle, boolean checked,
                           CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(title, 15, TEXT, false));
        if (subtitle != null) {
            TextView sub = text(subtitle, 11, MUTED, false);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(2);
            left.addView(sub, subLp);
        }
        row.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle);
        return row;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(bg(CARD, 18));
        card.setElevation(dp(1.5f));
        return card;
    }

    private TextView sectionTitle(String title) {
        TextView view = text(title, 14, 0xFF4A5060, true);
        view.setPadding(dp(4), 0, 0, 0);
        return view;
    }

    private TextView text(String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private View divider(LinearLayout.LayoutParams params) {
        View view = new View(this);
        view.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = params != null ? params
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = dp(1);
        view.setLayoutParams(lp);
        return view;
    }

    private GradientDrawable bg(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams marginTop(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px;
        return lp;
    }

    private int indexOf(String[] array, String value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(value)) return i;
        }
        return 0;
    }

    private void save() {
        Store.save(this, config);
    }

    private void openMomo() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.maimemo.android.momo");
        if (intent == null) {
            toast("没有找到墨墨背单词");
            return;
        }
        try {
            startActivity(intent);
        } catch (Throwable t) {
            toast("无法打开墨墨背单词");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
