package com.xmarthost.blocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** Parent settings screen, built for a TV remote (D-pad) but works with touch too. */
public class MainActivity extends Activity {

    private static final long UNLOCK_MS = 10 * 60 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextView status, details, diag;
    private LinearLayout actions;
    private boolean updateCheckedThisVisit;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        status = findViewById(R.id.status);
        details = findViewById(R.id.details);
        diag = findViewById(R.id.diag);
        actions = findViewById(R.id.actions);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.hasPin() && !prefs.isUnlocked()) {
            actions.removeAllViews();
            status.setText("Locked");
            details.setText("Enter the parent PIN to change settings.");
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
        if (!updateCheckedThisVisit) {
            updateCheckedThisVisit = true;
            checkUpdate(false);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        updateCheckedThisVisit = false;
    }

    // ------------------------------------------------------------------ UI

    private void render() {
        boolean enabled = isServiceEnabled();
        status.setText(enabled
                ? (prefs.blocking() ? "✅ Protection is ON" : "⏸ Protection is paused")
                : "⚠ Protection is OFF: turn on XMart Blocker in Accessibility");

        long count = prefs.getLong(Prefs.BLOCK_COUNT);
        long last = prefs.getLong(Prefs.LAST_BLOCK_AT);
        details.setText("Shorts blocked so far: " + count
                + (last > 0 ? "  (last " + DateUtils.getRelativeTimeSpanString(last) + ")" : "")
                + "\nVersion " + BuildConfig.VERSION_NAME
                + "\n\nWhat it does: when a YouTube Short opens, XMart Blocker presses Back and returns"
                + " to the YouTube page. Normal videos and dramas keep playing."
                + "\nIt starts by itself every time the TV turns on.");

        actions.removeAllViews();
        if (!enabled) addButton("Turn on protection (Accessibility settings)", v -> openAccessibilitySettings());
        addToggle("Blocking", Prefs.BLOCKING, prefs.blocking());
        addToggle("Show message when a Short is blocked", Prefs.SHOW_MESSAGE, prefs.showMessage());
        addToggle("Screen detection for the YouTube TV app", Prefs.SCREEN_DETECTION, prefs.screenDetection());
        addButton(prefs.hasPin() ? "Change parent PIN" : "Set parent PIN (recommended)", v -> setPin());
        if (prefs.hasPin()) {
            addToggle("Protect settings so the child cannot switch this off", Prefs.SETTINGS_GUARD, prefs.settingsGuard());
            addButton("Remove PIN", v -> {
                prefs.setPin(null);
                render();
            });
            addButton("Lock now", v -> {
                prefs.lockNow();
                finish();
            });
        }
        addButton("Check for updates", v -> checkUpdate(true));
        addButton("Refresh detection rules", v -> {
            ShortsBlockerService.refreshRulesNow();
            Toast.makeText(this, "Refreshing rules…", Toast.LENGTH_SHORT).show();
        });
        addButton("Setup help", v -> showHelp());
        addToggle("Diagnostics (for tuning)", Prefs.DIAGNOSTICS, prefs.diagnostics());
        if (prefs.diagnostics()) {
            addButton("Show diagnostics log", v -> {
                diag.setVisibility(View.VISIBLE);
                diag.setText(DiagLog.recent(60));
            });
            addButton("Clear diagnostics log", v -> {
                DiagLog.clear(this);
                diag.setText("");
            });
        } else {
            diag.setVisibility(View.GONE);
        }
        if (actions.getChildCount() > 0) actions.getChildAt(0).requestFocus();
    }

    private void addToggle(String label, String key, boolean value) {
        addButton(label + ":  " + (value ? "ON" : "OFF"), v -> {
            prefs.setBool(key, !value);
            render();
        });
    }

    private Button addButton(String label, View.OnClickListener l) {
        Button b = new Button(this, null, 0, R.style.Btn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(lp);
        b.setText(label);
        b.setOnClickListener(l);
        actions.addView(b);
        return b;
    }

    // ------------------------------------------------------------------ accessibility

    private boolean isServiceEnabled() {
        String list = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return ShortsBlockerService.isRunning();
        ComponentName me = new ComponentName(this, ShortsBlockerService.class);
        for (String s : list.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return ShortsBlockerService.isRunning();
    }

    private void openAccessibilitySettings() {
        prefs.unlockFor(UNLOCK_MS);
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            showHelp();
        }
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Setup help")
                .setMessage("1) Settings → Accessibility → XMart Blocker → On.\n\n"
                        + "2) If Android says \"Restricted setting\": Settings → Apps → XMart Blocker → "
                        + "⋮ / Allow restricted settings, then try step 1 again.\n\n"
                        + "3) If your TV hides these options, connect from a computer with ADB and run:\n"
                        + "adb shell appops set " + getPackageName() + " ACCESS_RESTRICTED_SETTINGS allow\n"
                        + "adb shell settings put secure enabled_accessibility_services "
                        + getPackageName() + "/.ShortsBlockerService\n"
                        + "adb shell settings put secure accessibility_enabled 1\n\n"
                        + "4) For one-click updates, allow \"Install unknown apps\" for XMart Blocker when asked.")
                .setPositiveButton("OK", null)
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
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
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
                render();
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
        new AlertDialog.Builder(this)
                .setTitle("Update available: " + r.version)
                .setMessage("You have " + BuildConfig.VERSION_NAME + "." + notes)
                .setPositiveButton("Update now", (d, w) -> startUpdate(r))
                .setNegativeButton("Later", null)
                .show();
    }

    private void startUpdate(Updater.Release r) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("One-time permission")
                    .setMessage("Allow XMart Blocker to install its own updates. On the next screen switch it ON, "
                            + "then come back and press Update again.")
                    .setPositiveButton("Open", (d, w) -> {
                        prefs.unlockFor(UNLOCK_MS);
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(this, "Open Settings → Apps → Special app access → "
                                    + "Install unknown apps → XMart Blocker", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        AlertDialog progress = new AlertDialog.Builder(this)
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
