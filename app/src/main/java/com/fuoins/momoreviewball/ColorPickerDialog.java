package com.fuoins.momoreviewball;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 动作取色器。
 *
 * <p>上面是一张标准的饱和度 / 明度颜色图（横向饱和度、纵向明度），下面一条色相条，
 * 点哪里颜色就变成哪里；再下面可以粘 {@code #RRGGBB} 色值。
 * 全程只改模块自己保存的颜色，不碰墨墨的任何资源。
 */
final class ColorPickerDialog {

    interface OnColorPicked {
        void onPicked(int color);
    }

    private ColorPickerDialog() {
    }

    static void show(Context context, String actionKey, int current, OnColorPicked callback) {
        if (!(context instanceof android.app.Activity)) return;
        final android.app.Activity activity = (android.app.Activity) context;

        final float[] hsv = new float[3];
        Color.colorToHSV(current | 0xFF000000, hsv);

        int padH = Util.dp(activity, 20);

        // 取色区自己一块，底部按钮单独一行。不用 AlertDialog 自带的按钮：
        // 墨墨的页面主题会把系统按钮的文字/背景配色覆盖掉，实测点开只剩一片白，
        // 看不到「确定」。这里全部自己画，颜色写死，任何主题下都能看见。
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padH, Util.dp(activity, 6), padH, Util.dp(activity, 2));

        TextView title = new TextView(activity);
        title.setText("「" + Config.labelOf(actionKey) + "」的颜色");
        title.setTextColor(0xFF1F2430);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.getPaint().setFakeBoldText(true);
        content.addView(title);

        final SvPanel sv = new SvPanel(activity);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(activity, 188));
        svLp.topMargin = Util.dp(activity, 12);
        content.addView(sv, svLp);

        final HueBar hueBar = new HueBar(activity);
        LinearLayout.LayoutParams hueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(activity, 24));
        hueLp.topMargin = Util.dp(activity, 12);
        content.addView(hueBar, hueLp);

        LinearLayout hexRow = new LinearLayout(activity);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hexRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hexRowLp.topMargin = Util.dp(activity, 14);
        content.addView(hexRow, hexRowLp);

        final View preview = new View(activity);
        preview.setLayoutParams(new LinearLayout.LayoutParams(
                Util.dp(activity, 30), Util.dp(activity, 30)));
        hexRow.addView(preview);

        final EditText hex = new EditText(activity);
        hex.setSingleLine(true);
        hex.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hex.setTextColor(0xFF1F2430);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hex.setFilters(new InputFilter.LengthFilter[]{new InputFilter.LengthFilter(9)});
        hex.setHint("#RRGGBB");
        hex.setBackgroundColor(Color.TRANSPARENT);
        hex.setPadding(Util.dp(activity, 10), 0, 0, 0);
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hexLp.leftMargin = Util.dp(activity, 8);
        hexRow.addView(hex, hexLp);

        TextView tip = new TextView(activity);
        tip.setText("点颜色图取色，或粘贴色值（支持 #RRGGBB / #AARRGGBB）");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tip.setTextColor(0xFF8A8F9C);
        tip.setPadding(0, Util.dp(activity, 8), 0, 0);
        content.addView(tip);

        final boolean[] updating = {false};

        Runnable syncHex = () -> {
            int color = Color.HSVToColor(hsv);
            updating[0] = true;
            hex.setText(String.format("#%06X", 0xFFFFFF & color));
            hex.setSelection(hex.getText().length());
            updating[0] = false;
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(color);
            bg.setStroke(Math.max(1, Util.dp(activity, 1.4f)), 0x33000000);
            preview.setBackground(bg);
        };

        Runnable syncAll = () -> {
            sv.setHsv(hsv[0], hsv[1], hsv[2]);
            hueBar.setHue(hsv[0]);
            syncHex.run();
        };

        sv.setOnChanged((s, v) -> {
            hsv[1] = s;
            hsv[2] = v;
            syncHex.run();
        });
        hueBar.setOnChanged(h -> {
            hsv[0] = h;
            sv.setHsv(hsv[0], hsv[1], hsv[2]);
            syncHex.run();
        });

        hex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (updating[0]) return;
                String text = s.toString().trim();
                if (!text.startsWith("#")) text = "#" + text;
                if (text.length() != 7 && text.length() != 9) return;
                try {
                    int color = Color.parseColor(text);
                    float[] out = new float[3];
                    Color.colorToHSV(color, out);
                    hsv[0] = out[0];
                    hsv[1] = out[1];
                    hsv[2] = out[2];
                    sv.setHsv(hsv[0], hsv[1], hsv[2]);
                    hueBar.setHue(hsv[0]);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setShape(GradientDrawable.OVAL);
                    bg.setColor(color | 0xFF000000);
                    bg.setStroke(Math.max(1, Util.dp(activity, 1.4f)), 0x33000000);
                    preview.setBackground(bg);
                } catch (Throwable ignored) {
                }
            }
        });

        syncAll.run();

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(padH, Util.dp(activity, 8), padH, Util.dp(activity, 10));

        TextView resetDefault = new TextView(activity);
        resetDefault.setText("恢复默认");
        resetDefault.setTextColor(0xFF2F3E6E);
        resetDefault.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        resetDefault.setPadding(0, Util.dp(activity, 10), 0, Util.dp(activity, 10));
        resetDefault.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(resetDefault);

        TextView cancel = new TextView(activity);
        cancel.setText("取消");
        cancel.setTextColor(0xFF8A8F9C);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cancel.setPadding(Util.dp(activity, 14), Util.dp(activity, 10),
                Util.dp(activity, 14), Util.dp(activity, 10));
        actions.addView(cancel);

        TextView confirm = new TextView(activity);
        confirm.setText("确定");
        confirm.setTextColor(0xFFFFFFFF);
        confirm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        confirm.getPaint().setFakeBoldText(true);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(0xFF2F3E6E);
        okBg.setCornerRadius(Util.dp(activity, 18));
        confirm.setBackground(okBg);
        confirm.setPadding(Util.dp(activity, 22), Util.dp(activity, 9),
                Util.dp(activity, 22), Util.dp(activity, 9));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.leftMargin = Util.dp(activity, 6);
        actions.addView(confirm, okLp);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);
        root.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(
                activity, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setView(root)
                .create();

        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            if (callback != null) callback.onPicked(Color.HSVToColor(hsv) | 0xFF000000);
            dialog.dismiss();
        });
        // 「恢复默认」只把取色器拨回默认色，仍要点确定才生效，避免误触
        resetDefault.setOnClickListener(v -> {
            float[] def = new float[3];
            Color.colorToHSV(Config.defaultColorOf(actionKey), def);
            hsv[0] = def[0];
            hsv[1] = def[1];
            hsv[2] = def[2];
            syncAll.run();
        });

        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        // 默认的 Dialog 宽度偏窄，颜色图会被压成一条；给到屏幕的 92%
        try {
            if (dialog.getWindow() != null) {
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
                dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 饱和度 / 明度颜色图。 */
    private static final class SvPanel extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clip = new Path();
        private float hue;
        private float sat = 1f;
        private float val = 1f;
        private int radius;
        private float lastX;
        private float lastY;

        SvPanel(Context context) {
            super(context);
            radius = Util.dp(context, 12);
            dot.setStyle(Paint.Style.STROKE);
        }

        void setHsv(float h, float s, float v) {
            hue = h;
            sat = s;
            val = v;
            invalidate();
        }

        void setOnChanged(OnSvChanged listener) {
            this.listener = listener;
        }

        private OnSvChanged listener;

        interface OnSvChanged {
            void onChanged(float saturation, float value);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);

            paint.setShader(null);
            paint.setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
            canvas.drawRect(rect, paint);

            paint.setShader(new LinearGradient(0, 0, w, 0,
                    0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);

            paint.setShader(new LinearGradient(0, 0, 0, h,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
            canvas.restore();

            float cx = sat * w;
            float cy = (1f - val) * h;
            dot.setStrokeWidth(Math.max(1.5f, Util.dp(getContext(), 2f)));
            dot.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, Util.dp(getContext(), 7), dot);
            dot.setStrokeWidth(Math.max(1f, Util.dp(getContext(), 1f)));
            dot.setColor(0x55000000);
            canvas.drawCircle(cx, cy, Util.dp(getContext(), 9), dot);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    lastX = clamp(event.getX() / Math.max(1f, getWidth()));
                    lastY = clamp(event.getY() / Math.max(1f, getHeight()));
                    sat = lastX;
                    val = 1f - lastY;
                    invalidate();
                    if (listener != null) listener.onChanged(sat, val);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }
    }

    /** 色相彩虹条。 */
    private static final class HueBar extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clip = new Path();
        private final int[] COLORS = {
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
        };
        private float hue;
        private int radius;

        HueBar(Context context) {
            super(context);
            radius = Util.dp(context, 12);
            mark.setStyle(Paint.Style.STROKE);
        }

        void setHue(float h) {
            hue = h;
            invalidate();
        }

        private OnHueChanged listener;

        void setOnChanged(OnHueChanged listener) {
            this.listener = listener;
        }

        interface OnHueChanged {
            void onChanged(float hue);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            paint.setShader(new LinearGradient(0, 0, w, 0, COLORS, null,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
            canvas.restore();

            float x = hue / 360f * w;
            mark.setStrokeWidth(Math.max(1.5f, Util.dp(getContext(), 2f)));
            mark.setColor(0xFFFFFFFF);
            canvas.drawLine(x, Util.dp(getContext(), 2),
                    x, h - Util.dp(getContext(), 2), mark);
            mark.setStrokeWidth(Math.max(1f, Util.dp(getContext(), 1f)));
            mark.setColor(0x66000000);
            canvas.drawLine(x + Util.dp(getContext(), 2), Util.dp(getContext(), 2),
                    x + Util.dp(getContext(), 2), h - Util.dp(getContext(), 2), mark);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    hue = clamp(event.getX() / Math.max(1f, getWidth())) * 360f;
                    if (hue >= 360f) hue = 359.9f;
                    invalidate();
                    if (listener != null) listener.onChanged(hue);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
