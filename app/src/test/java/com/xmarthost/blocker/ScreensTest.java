package com.xmarthost.blocker;

import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/** Opens each screen (catches crashes) and saves a picture of it to build/screens/. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreensTest {

    private void shoot(Activity a, String name) throws Exception {
        ShadowLooper.idleMainLooper(4000, java.util.concurrent.TimeUnit.MILLISECONDS);
        View root = a.getWindow().getDecorView();
        int w = root.getResources().getDisplayMetrics().widthPixels;
        int h = root.getResources().getDisplayMetrics().heightPixels;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        File dir = new File("build/screens");
        dir.mkdirs();
        try (FileOutputStream os = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
    }

    /** Draws the whole scrollable page, not just the visible part. */
    private void shootFull(Activity a, String name) throws Exception {
        ShadowLooper.idleMainLooper(4000, java.util.concurrent.TimeUnit.MILLISECONDS);
        android.widget.ScrollView sv = (android.widget.ScrollView)
                ((android.view.ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
        View page = sv.getChildAt(0);
        int w = a.getResources().getDisplayMetrics().widthPixels;
        page.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = page.getMeasuredHeight();
        page.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF0B1417);
        page.draw(new Canvas(b));
        File dir = new File("build/screens");
        dir.mkdirs();
        try (FileOutputStream os = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void phoneFullStopped() throws Exception {
        android.content.Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        android.provider.Settings.Secure.putString(ctx.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "com.xmarthost.blocker/com.xmarthost.blocker.ShortsBlockerService");
        Prefs p = new Prefs(ctx);
        p.recordBlock(); p.recordBlock(); p.recordBlock();
        p.setPin("1234");
        p.unlockFor(60_000);
        p.setBool(Prefs.DIAGNOSTICS, true);
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        shootFull(c.get(), "phone-full-stopped");
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void phoneFullOff() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        shootFull(c.get(), "phone-full-off");
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    public void tvMain() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        assertNotNull(c.get());
        shoot(c.get(), "tv-main");
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    public void tvSplash() throws Exception {
        ActivityController<SplashActivity> c = Robolectric.buildActivity(SplashActivity.class).create().start().resume().visible();
        ShadowLooper.idleMainLooper(1800, java.util.concurrent.TimeUnit.MILLISECONDS);
        shoot(c.get(), "tv-splash");
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void phoneMain() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        shoot(c.get(), "phone-main");
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void phoneSplash() throws Exception {
        ActivityController<SplashActivity> c = Robolectric.buildActivity(SplashActivity.class).create().start().resume().visible();
        ShadowLooper.idleMainLooper(1800, java.util.concurrent.TimeUnit.MILLISECONDS);
        shoot(c.get(), "phone-splash");
    }
}
