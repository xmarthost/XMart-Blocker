package com.xmarthost.blocker;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Branded welcome screen shown for ~3.5 seconds before the main screen. */
public class SplashActivity extends Activity {

    private static final long DURATION_MS = 3500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean done;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui ui = new Ui(this);

        boolean shortScreen = getResources().getConfiguration().screenHeightDp < 620;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{getColor(R.color.bg_top), ui.bg, 0xFF071012});
        root.setBackground(bg);

        // Centre: X mark, name, tagline
        LinearLayout centre = ui.column();
        centre.setGravity(Gravity.CENTER_HORIZONTAL);

        View tile = ui.brandTile(shortScreen ? 104 : 132);
        tile.setElevation(ui.dp(12));
        centre.addView(tile);

        TextView name = ui.text("XMart Blocker", shortScreen ? 32 : 38, ui.text, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, ui.dp(shortScreen ? 16 : 26), 0, 0);
        centre.addView(name);

        TextView tag = ui.text("YouTube Shorts Guard for your family", 17, ui.accentBright, false);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(0, ui.dp(6), 0, 0);
        centre.addView(tag);

        // Progress bar
        FrameLayout track = new FrameLayout(this);
        track.setBackground(ui.round(0x33FFFFFF, 4));
        View fill = new View(this);
        fill.setBackground(ui.round(ui.accentBright, 4));
        track.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tp = ui.lp(ui.dp(220), ui.dp(5));
        tp.topMargin = ui.dp(shortScreen ? 22 : 34);
        centre.addView(track, tp);

        TextView status = ui.text("Starting protection…", 13.5f, ui.muted, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, ui.dp(10), 0, 0);
        centre.addView(status);

        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        root.addView(centre, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // Footer: XMart Host logo on a white pill + version
        LinearLayout footer = ui.column();
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView by = ui.text("Powered by", 12, ui.muted, false);
        by.setGravity(Gravity.CENTER);
        footer.addView(by);
        FrameLayout pill = new FrameLayout(this);
        pill.setBackground(ui.round(0xFFFFFFFF, 40));
        pill.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.brand_logo);
        logo.setAdjustViewBounds(true);
        pill.addView(logo, new FrameLayout.LayoutParams(ui.dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams pp = ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = ui.dp(6);
        footer.addView(pill, pp);
        TextView ver = ui.text("v" + BuildConfig.VERSION_NAME + "  ·  xmarthost.com", 12, ui.muted, false);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, ui.dp(10), 0, 0);
        footer.addView(ver);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.bottomMargin = ui.dp(shortScreen ? 18 : 28);
        fp.topMargin = ui.dp(16);
        root.addView(footer, fp);

        setContentView(root);

        // Animations
        tile.setScaleX(0.4f);
        tile.setScaleY(0.4f);
        tile.setAlpha(0f);
        tile.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(750)
                .setInterpolator(new OvershootInterpolator(1.6f)).start();
        for (View v : new View[]{name, tag, track, status, footer}) {
            v.setAlpha(0f);
            v.setTranslationY(ui.dp(18));
        }
        name.animate().alpha(1f).translationY(0).setStartDelay(350).setDuration(600)
                .setInterpolator(new DecelerateInterpolator()).start();
        tag.animate().alpha(1f).translationY(0).setStartDelay(550).setDuration(600).start();
        track.animate().alpha(1f).translationY(0).setStartDelay(700).setDuration(500).start();
        status.animate().alpha(1f).translationY(0).setStartDelay(750).setDuration(500).start();
        footer.animate().alpha(1f).translationY(0).setStartDelay(900).setDuration(700).start();

        ObjectAnimator pulse = ObjectAnimator.ofFloat(tile, View.ROTATION_Y, 0f, 360f);
        pulse.setStartDelay(1500);
        pulse.setDuration(1100);
        pulse.setInterpolator(new DecelerateInterpolator());
        pulse.start();

        ValueAnimator progress = ValueAnimator.ofInt(0, ui.dp(220));
        progress.setStartDelay(700);
        progress.setDuration(DURATION_MS - 900);
        progress.setInterpolator(new LinearInterpolator());
        String[] steps = {"Starting protection…", "Checking YouTube guard…", "Loading your settings…", "Ready"};
        progress.addUpdateListener(a -> {
            int w = (int) a.getAnimatedValue();
            ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = w;
            fill.setLayoutParams(lp);
            int i = Math.min(steps.length - 1, (int) (a.getAnimatedFraction() * steps.length));
            status.setText(steps[i]);
        });
        progress.start();

        handler.postDelayed(this::openMain, DURATION_MS);
    }

    private void openMain() {
        if (done || isFinishing()) return;
        done = true;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Let the welcome screen finish.
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
