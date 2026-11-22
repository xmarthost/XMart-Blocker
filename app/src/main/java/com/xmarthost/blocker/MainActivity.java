package com.xmarthost.blocker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** Parent dashboard. Built in code so it adapts to TVs (two columns) and phones (one column). */
public class MainActivity extends Activity {

    private static final long UNLOCK_MS = 10 * 60 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private Ui ui;
    private ScrollView scroll;
    private boolean updateCheckedThisVisit;
    private int focusIdAfterRender = View.NO_ID;

    private final Runnable liveRefresh = new Runnable() {
        @Override
        public void run() {
            if (!prefs.hasPin() || prefs.isUnlocked()) refreshKeepingFocus();
            main.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        ui = new Ui(this);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ui.bg);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.hasPin() && !prefs.isUnlocked()) {
            renderLocked();
            askPin("Parent PIN", pin -> {
                if (prefs.checkPin(pin)) {
                    prefs.unlockFor(UNLOCK_MS);
                    render();
                } else {
                    Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }, this::finish);
            return;
        }
        render();
        main.postDelayed(liveRefresh, 5000);
        askNotificationPermissionOnce();
        if (!updateCheckedThisVisit) {
            updateCheckedThisVisit = true;
            checkUpdate(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(liveRefresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        updateCheckedThisVisit = false;
    }

    // ------------------------------------------------------------------ layout

    private void renderLocked() {
        LinearLayout page = ui.column();
        page.setGravity(Gravity.CENTER);
        page.setPadding(ui.dp(32), ui.dp(80), ui.dp(32), ui.dp(32));
        page.addView(ui.brandTile(96));
        TextView t = ui.text("Locked", 26, ui.text, true);
        t.setPadding(0, ui.dp(20), 0, ui.dp(6));
        page.addView(t);
        page.addView(ui.text("Enter the parent PIN to open XMart Blocker.", 15, ui.muted, false));
        scroll.removeAllViews();
        scroll.addView(page);
    }

    private void refreshKeepingFocus() {
        View f = getCurrentFocus();
        focusIdAfterRender = f != null ? f.getId() : View.NO_ID;
        int y = scroll.getScrollY();
        render();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void render() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        int side = wide ? 48 : 18;

        LinearLayout page = ui.column();
        page.setPadding(ui.dp(side), ui.dp(wide ? 28 : 16), ui.dp(side), ui.dp(32));
        page.addView(header());

        LinearLayout left = ui.column();
        LinearLayout right = ui.column();
        left.addView(hero());
        left.addView(stats());
        buildKeepRunning(left);
        buildProtection(right);
        buildParent(right);
        buildUpdates(right);
        buildAdvanced(right);

        if (wide) {
            LinearLayout cols = ui.row();
            cols.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = ui.dp(28);
            cols.addView(left, lp);
            cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));
            page.addView(cols, ui.matchWrap(18));
        } else {
            page.addView(left, ui.matchWrap(14));
            page.addView(right, ui.matchWrap(0));
        }

        TextView foot = ui.text("© XMart Host · xmarthost.com", 12, ui.muted, false);
        foot.setGravity(Gravity.CENTER);
        page.addView(foot, ui.matchWrap(30));

        scroll.removeAllViews();
        scroll.addView(page);

        View toFocus = focusIdAfterRender != View.NO_ID ? page.findViewById(focusIdAfterRender) : null;
        focusIdAfterRender = View.NO_ID;
        if (toFocus != null) toFocus.requestFocus();
        else {
            View first = page.findViewById(R.id.hero_action);
            if (first == null) first = page.findFocus();
            if (first != null) first.requestFocus();
        }
    }

    private View header() {
        LinearLayout h = ui.row();
        h.addView(ui.brandTile(52));
        LinearLayout t = ui.column();
        t.setPadding(ui.dp(14), 0, 0, 0);
        t.addView(ui.text("XMart Blocker", 26, ui.text, true));
        t.addView(ui.text("YouTube Shorts Guard · by XMart Host", 13.5f, ui.muted, false));
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = ui.text("v" + BuildConfig.VERSION_NAME, 13, ui.accentBright, true);
        v.setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5));
        v.setBackground(ui.round(ui.surface, 30, ui.stroke, 1));
        h.addView(v);
        return h;
    }

    private View hero() {
        Health.State st = Health.state(this);
        boolean paused = st == Health.State.ON && !prefs.blocking();
        int color = st == Health.State.ON ? (paused ? ui.warn : ui.good) : (st == Health.State.STOPPED ? ui.warn : ui.bad);

        LinearLayout card = ui.column();
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(color, ui.surface, 0.30f), ui.surface});
        g.setCornerRadius(ui.dp(20));
        g.setStroke(ui.dp(1.5f), blend(color, ui.surface, 0.55f));
        card.setBackground(g);
        card.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(22));

        LinearLayout top = ui.row();
        ImageView icon = new ImageView(this);
        icon.setImageResource(st == Health.State.ON && !paused ? R.drawable.ic_shield_ok : R.drawable.ic_shield_warn);
        icon.setColorFilter(color);
        top.addView(icon, ui.lp(ui.dp(54), ui.dp(54)));

        String title, sub;
        if (st == Health.State.OFF) {
            title = "Protection is OFF";
            sub = "Turn on XMart Blocker in Accessibility to start blocking Shorts.";
        } else if (st == Health.State.STOPPED) {
            title = "Protection stopped";
            sub = "Android stopped the guard (battery saver). Turn it OFF and ON again in Accessibility, "
                    + "and allow it in the \"Keep it running\" section below.";
        } else if (paused) {
            title = "Protection paused";
            sub = "Blocking is switched off below.";
        } else {
            title = "Protection is ON";
            sub = "Shorts close as soon as they open. Normal videos and dramas keep working.";
        }
        LinearLayout tt = ui.column();
        tt.setPadding(ui.dp(16), 0, 0, 0);
        tt.addView(ui.text(title, 23, ui.text, true));
        TextView s = ui.text(sub, 14, ui.muted, false);
        s.setPadding(0, ui.dp(4), 0, 0);
        tt.addView(s);
        top.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(top);

        if (st != Health.State.ON) {
            TextView btn = ui.primaryButton(st == Health.State.OFF ? "Turn on protection" : "Restart protection",
                    ui.accent, v -> openAccessibilitySettings());
            btn.setId(R.id.hero_action);
            LinearLayout.LayoutParams bl = ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = ui.dp(16);
            card.addView(btn, bl);
        }
        return card;
    }

    private View stats() {
        LinearLayout r = ui.row();
        long last = prefs.getLong(Prefs.LAST_BLOCK_AT);
        String lastText = last <= 0 ? "—" : shortAgo(System.currentTimeMillis() - last);
        View[] tiles = {
                ui.stat(String.valueOf(prefs.blockedToday()), "Blocked today"),
                ui.stat(String.valueOf(prefs.getLong(Prefs.BLOCK_COUNT)), "Total blocked"),
                ui.stat(lastText, "Last block"),
        };
        for (int i = 0; i < tiles.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = ui.dp(10);
            r.addView(tiles[i], lp);
        }
        r.setLayoutParams(ui.matchWrap(14));
        return r;
    }

    private static String shortAgo(long ms) {
        long min = ms / 60_000;
        if (min < 1) return "Just now";
        if (min < 60) return min + "m ago";
        long h = min / 60;
        if (h < 24) return h + "h ago";
        return (h / 24) + "d ago";
    }

    private void buildKeepRunning(LinearLayout col) {
        boolean tv = Health.isTv(this);
        boolean battery = Health.batteryUnrestricted(this);
        boolean showAutostart = !tv && Health.needsAutostart();
        col.addView(ui.sectionTitle("Keep it running"));
        col.addView(ui.settingRow("Battery: no restrictions",
                battery ? "Allowed. Android will not stop the guard to save battery."
                        : "Recommended. Stops the phone/TV from killing the guard in the background.",
                battery ? "OK" : "FIX", battery ? ui.good : ui.warn, v -> openBattery()));
        if (showAutostart) {
            col.addView(ui.settingRow("Autostart (" + Build.MANUFACTURER + ")",
                    "Allow XMart Blocker in Autostart / Background start so it survives restarts.",
                    "OPEN", ui.accent, v -> openAutostart()));
        }
        col.addView(ui.settingRow("Keep-alive notification",
                "A quiet \"Protection active\" notification keeps the guard alive on strict phones.",
                prefs.keepAlive() ? "ON" : "OFF", prefs.keepAlive() ? ui.accent : ui.stroke, v -> {
                    prefs.setBool(Prefs.KEEP_ALIVE, !prefs.keepAlive());
                    ShortsBlockerService.applyKeepAliveNow();
                    refreshKeepingFocus();
                }));
    }

    private void buildProtection(LinearLayout col) {
        col.addView(ui.sectionTitle("Protection"));
        toggle(col, "Block YouTube Shorts", "Close Shorts and go back to the YouTube page.", Prefs.BLOCKING, prefs.blocking());
        toggle(col, "Show a message", "Show \"Shorts are blocked\" when one is closed.", Prefs.SHOW_MESSAGE, prefs.showMessage());
        toggle(col, "TV screen detection", "Needed for the YouTube app on Android TV.", Prefs.SCREEN_DETECTION, prefs.screenDetection());
    }

    private void buildParent(LinearLayout col) {
        col.addView(ui.sectionTitle("Parent lock"));
        col.addView(ui.settingRow(prefs.hasPin() ? "Change parent PIN" : "Set parent PIN",
                prefs.hasPin() ? "PIN is set." : "Recommended. Stops children changing these settings.",
                prefs.hasPin() ? "SET" : "NEW", prefs.hasPin() ? ui.good : ui.warn, v -> setPin()));
        if (prefs.hasPin()) {
            toggle(col, "Protect system settings",
                    "Closes Settings screens that could switch XMart Blocker off or uninstall it.",
                    Prefs.SETTINGS_GUARD, prefs.settingsGuard());
            col.addView(ui.settingRow("Lock now", "Ask for the PIN next time.", null, 0, v -> {
                prefs.lockNow();
                finish();
            }));
            col.addView(ui.settingRow("Remove PIN", null, null, 0, v -> {
                prefs.setPin(null);
                refreshKeepingFocus();
            }));
        }
    }

    private void buildUpdates(LinearLayout col) {
        col.addView(ui.sectionTitle("Updates"));
        col.addView(ui.settingRow("Check for updates", "Installed: v" + BuildConfig.VERSION_NAME, null, 0,
                v -> checkUpdate(true)));
        col.addView(ui.settingRow("Refresh detection rules", "Downloads the latest Shorts rules from GitHub.", null, 0, v -> {
            ShortsBlockerService.refreshRulesNow();
            Toast.makeText(this, "Refreshing rules…", Toast.LENGTH_SHORT).show();
        }));
    }

    private void buildAdvanced(LinearLayout col) {
        col.addView(ui.sectionTitle("Help & diagnostics"));
        col.addView(ui.settingRow("Setup help", "Restricted settings, ADB and TV tips.", null, 0, v -> showHelp()));
        toggle(col, "Diagnostics", "Records what the guard sees, for tuning.", Prefs.DIAGNOSTICS, prefs.diagnostics());
        String crash = prefs.getString(Prefs.LAST_CRASH);
        if (prefs.diagnostics() || crash != null) {
            col.addView(ui.settingRow("Show diagnostics log",
                    crash != null ? "Last problem: " + DateUtils.getRelativeTimeSpanString(prefs.getLong(Prefs.LAST_CRASH_AT)) : null,
                    null, 0, v -> showLog()));
        }
    }

    private void toggle(LinearLayout col, String title, String sub, String key, boolean value) {
        View r = ui.settingRow(title, sub, value ? "ON" : "OFF", value ? ui.accent : ui.stroke, v -> {
            prefs.setBool(key, !value);
            refreshKeepingFocus();
        });
        r.setId(key.hashCode() & 0x7fffffff);
        col.addView(r);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return 0xFF000000 | ((int) (br + (ar - br) * t) << 16) | ((int) (bg + (ag - bg) * t) << 8) | (int) (bb + (ab - bb) * t);
    }

    // ------------------------------------------------------------------ accessibility / system screens

    private void openAccessibilitySettings() {
        prefs.unlockFor(UNLOCK_MS);
        boolean stopped = Health.state(this) == Health.State.STOPPED;
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(stopped ? "Restart XMart Blocker" : "Turn on XMart Blocker")
                .setMessage((stopped ? "On the next screen switch XMart Blocker OFF, then ON again.\n\n"
                        : "On the next screen switch ON \"XMart Blocker\".\n")
                        + "If you see the whole Accessibility list, look under \"Downloaded apps\" / "
                        + "\"Installed apps\" / \"Installed services\".\n\n"
                        + "Grey or \"Restricted setting\"? Press \"App info\" → ⋮ (top right) → "
                        + "\"Allow restricted settings\", then try again.\n\n"
                        + "Urdu: Agle page par XMart Blocker ko ON karein. Agar grey ho to \"App info\" → ⋮ → "
                        + "\"Allow restricted settings\" karein, phir dobara try karein.")
                .setPositiveButton("Open settings", (d, w) -> openServicePage())
                .setNeutralButton("App info", (d, w) -> openAppInfo())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openServicePage() {
        ComponentName me = Health.component(this);
        try {
            Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            i.putExtra(Intent.EXTRA_COMPONENT_NAME, me.flattenToString());
            startActivity(i);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
        if (!tryStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) showHelp();
    }

    private void openAppInfo() {
        prefs.unlockFor(UNLOCK_MS);
        if (!tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())))) showHelp();
    }

    private void openBattery() {
        prefs.unlockFor(UNLOCK_MS);
        if (Health.batteryUnrestricted(this)) {
            Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!tryStart(Health.batteryIntent(this))
                && !tryStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            openAppInfo();
        }
    }

    private void openAutostart() {
        prefs.unlockFor(UNLOCK_MS);
        for (Intent i : Health.autostartIntents(this)) {
            if (tryStart(i)) {
                Toast.makeText(this, "Find XMart Blocker and allow it", Toast.LENGTH_LONG).show();
                return;
            }
        }
    }

    private boolean tryStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private void askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33 || Health.isTv(this) || !prefs.keepAlive()) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        if (prefs.getBool(Prefs.NOTIF_ASKED, false)) return;
        prefs.setBool(Prefs.NOTIF_ASKED, true);
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
    }

    private void showHelp() {
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Setup help")
                .setMessage("1) Settings → Accessibility → XMart Blocker → On.\n\n"
                        + "2) \"Restricted setting\"? Settings → Apps → XMart Blocker → ⋮ → "
                        + "Allow restricted settings, then repeat step 1.\n\n"
                        + "3) Phones (Xiaomi, Oppo, Vivo…): allow Autostart and set Battery to "
                        + "\"No restrictions\" so Android does not stop the guard.\n\n"
                        + "4) TV hides these menus? From a computer with ADB:\n"
                        + "adb shell appops set " + getPackageName() + " ACCESS_RESTRICTED_SETTINGS allow\n"
                        + "adb shell settings put secure enabled_accessibility_services "
                        + getPackageName() + "/.ShortsBlockerService\n"
                        + "adb shell settings put secure accessibility_enabled 1\n\n"
                        + "5) For one-click updates, allow \"Install unknown apps\" for XMart Blocker when asked.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showLog() {
        String crash = prefs.getString(Prefs.LAST_CRASH);
        String body = (crash != null ? "LAST PROBLEM\n" + crash + "\n\n" : "")
                + "RECENT EVENTS\n" + DiagLog.recent(80);
        TextView t = ui.text(body, 11, ui.text, false);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Diagnostics")
                .setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear", (d, w) -> {
                    DiagLog.clear(this);
                    prefs.setString(Prefs.LAST_CRASH, null);
                    refreshKeepingFocus();
                })
                .show();
    }

    // ------------------------------------------------------------------ PIN

    interface PinCallback {
        void onPin(String pin);
    }

    private void askPin(String title, PinCallback ok, Runnable cancel) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("4–8 digits");
        LinearLayout wrap = ui.column();
        wrap.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0);
        wrap.addView(input);
        AlertDialog d = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("OK", (di, w) -> ok.onPin(input.getText().toString().trim()))
                .setNegativeButton("Cancel", (di, w) -> {
                    if (cancel != null) cancel.run();
                })
                .setOnCancelListener(di -> {
                    if (cancel != null) cancel.run();
                })
                .create();
        d.show();
        input.requestFocus();
    }

    private void setPin() {
        askPin("New parent PIN", pin -> {
            if (pin.length() < 4 || pin.length() > 8) {
                Toast.makeText(this, "PIN must be 4–8 digits", Toast.LENGTH_SHORT).show();
                return;
            }
            askPin("Repeat the PIN", again -> {
                if (!again.equals(pin)) {
                    Toast.makeText(this, "PINs did not match", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.setPin(pin);
                prefs.unlockFor(UNLOCK_MS);
                Toast.makeText(this, "PIN saved", Toast.LENGTH_SHORT).show();
                refreshKeepingFocus();
            }, null);
        }, null);
    }

    // ------------------------------------------------------------------ updates

    private void checkUpdate(boolean userAsked) {
        if (userAsked) Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Updater.Release r = Updater.checkForUpdate();
                main.post(() -> {
                    if (isFinishing()) return;
                    if (r == null) {
                        if (userAsked) Toast.makeText(this, "You have the latest version", Toast.LENGTH_SHORT).show();
                    } else {
                        offerUpdate(r);
                    }
                });
            } catch (Exception e) {
                if (userAsked) {
                    main.post(() -> Toast.makeText(this, "Update check failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show());
                }
            }
        }, "update-check").start();
    }

    private void offerUpdate(Updater.Release r) {
        String notes = r.notes == null || r.notes.isEmpty() ? "" : "\n\n" + (r.notes.length() > 600
                ? r.notes.substring(0, 600) + "…" : r.notes);
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Update available: " + r.version)
                .setMessage("You have v" + BuildConfig.VERSION_NAME + "." + notes)
                .setPositiveButton("Update now", (d, w) -> startUpdate(r))
                .setNegativeButton("Later", null)
                .show();
    }

    private void startUpdate(Updater.Release r) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle("One-time permission")
                    .setMessage("Allow XMart Blocker to install its own updates. On the next screen switch it ON, "
                            + "then come back and press Update again.")
                    .setPositiveButton("Open", (d, w) -> {
                        prefs.unlockFor(UNLOCK_MS);
                        if (!tryStart(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())))) {
                            Toast.makeText(this, "Open Settings → Apps → Special app access → "
                                    + "Install unknown apps → XMart Blocker", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        AlertDialog progress = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Updating to " + r.version)
                .setMessage("Downloading… 0%")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            try {
                File apk = Updater.download(this, r,
                        pct -> main.post(() -> progress.setMessage("Downloading… " + pct + "%")));
                main.post(() -> progress.setMessage("Installing…"));
                Updater.install(this, apk);
                main.postDelayed(progress::dismiss, 1500);
            } catch (Exception e) {
                main.post(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "update-install").start();
    }
}
