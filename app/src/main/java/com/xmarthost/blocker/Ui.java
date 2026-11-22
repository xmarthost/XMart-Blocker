package com.xmarthost.blocker;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view factory so the screens look consistent and work with a TV remote (D-pad). */
final class Ui {
    final Context c;
    final int bg, surface, surface2, stroke, accent, accentBright, text, muted, good, warn, bad;

    Ui(Context c) {
        this.c = c;
        bg = c.getColor(R.color.bg);
        surface = c.getColor(R.color.surface);
        surface2 = c.getColor(R.color.surface2);
        stroke = c.getColor(R.color.stroke);
        accent = c.getColor(R.color.accent);
        accentBright = c.getColor(R.color.accent_bright);
        text = c.getColor(R.color.text);
        muted = c.getColor(R.color.muted);
        good = c.getColor(R.color.good);
        warn = c.getColor(R.color.warn);
        bad = c.getColor(R.color.bad);
    }

    /**
     * Android 15+ draws apps edge-to-edge, under the status and navigation bars. Pads the view by
     * the bar sizes (and any display cut-out) so nothing hides behind them.
     */
    static void padForSystemBars(View v) {
        final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom);
            return insets;
        });
        v.requestApplyInsets();
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    GradientDrawable round(int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable g = round(color, radiusDp);
        g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    /** Background that lights up with a bright border when focused by the remote. */
    Drawable focusable(int normal, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        Drawable focused = round(surface2, radiusDp, accentBright, 2.5f);
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        s.addState(new int[]{}, round(normal, radiusDp, stroke, 1));
        return s;
    }

    TextView text(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    LinearLayout.LayoutParams matchWrap(int topMarginDp) {
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topMarginDp);
        return p;
    }

    /** White rounded tile holding the XMart X mark. */
    View brandTile(int sizeDp) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(round(0xFFFFFFFF, sizeDp * 0.24f));
        ImageView iv = new ImageView(c);
        iv.setImageResource(R.drawable.brand_x);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(sizeDp * 0.14f);
        f.setPadding(pad, pad, pad, pad);
        f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        f.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return f;
    }

    TextView sectionTitle(String s) {
        TextView t = text(s.toUpperCase(), 12.5f, accentBright, true);
        t.setLetterSpacing(0.12f);
        t.setLayoutParams(matchWrap(26));
        t.setPadding(dp(4), 0, 0, dp(2));
        return t;
    }

    /** A focusable settings row: title, optional subtitle, and a value pill on the right. */
    LinearLayout settingRow(String title, String subtitle, String pill, int pillColor, View.OnClickListener l) {
        LinearLayout r = row();
        r.setFocusable(true);
        r.setClickable(true);
        r.setBackground(focusable(surface, 14));
        r.setPadding(dp(18), dp(14), dp(14), dp(14));
        r.setLayoutParams(matchWrap(10));

        LinearLayout texts = column();
        TextView t = text(title, 17, text, true);
        texts.addView(t);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView s = text(subtitle, 13.5f, muted, false);
            s.setPadding(0, dp(3), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (pill != null) {
            TextView p = text(pill, 13, 0xFFFFFFFF, true);
            p.setGravity(Gravity.CENTER);
            p.setMinWidth(dp(64));
            p.setPadding(dp(12), dp(6), dp(12), dp(6));
            p.setBackground(round(pillColor, 50));
            LinearLayout.LayoutParams pl = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pl.leftMargin = dp(12);
            r.addView(p, pl);
        } else {
            TextView chevron = text("›", 26, muted, false);
            chevron.setPadding(dp(12), 0, dp(4), 0);
            r.addView(chevron);
        }
        r.setOnClickListener(l);
        return r;
    }

    TextView primaryButton(String label, int color, View.OnClickListener l) {
        TextView b = text(label, 16, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(22), dp(12), dp(22), dp(12));
        StateListDrawable s = new StateListDrawable();
        Drawable focused = round(color, 40, 0xFFFFFFFF, 2.5f);
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        s.addState(new int[]{}, round(color, 40));
        b.setBackground(s);
        b.setOnClickListener(l);
        return b;
    }

    /** Small stat tile: big number + label. */
    LinearLayout stat(String value, String label) {
        LinearLayout t = column();
        t.setBackground(round(surface, 14, stroke, 1));
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        // Numbers get the big size; words like "Just now" a smaller one so they fit.
        TextView v = text(value, value.matches("[0-9—]+") ? 24 : 18, text, true);
        v.setMinHeight(dp(32));
        v.setGravity(android.view.Gravity.CENTER_VERTICAL);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(v);
        TextView l = text(label, 12.5f, muted, false);
        t.addView(l);
        return t;
    }
}
