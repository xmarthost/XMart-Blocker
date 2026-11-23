package com.xmarthost.blocker;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The background guard. Android starts it automatically on every boot once it is switched on in
 * Settings → Accessibility, so no launcher or boot receiver is needed.
 *
 * Detectors:
 *  1. Accessibility tree: Shorts-only view ids (reel_recycler, reel_player_page_container,
 *     reel_progress_bar, …), the same signals used by open-source blockers such as ShortStop and
 *     Shorts-Blocker. Runs on every YouTube event and, as a safety net, on a timer while YouTube
 *     is open, so a missed event cannot let a Short through.
 *  2. Screen analysis for YouTube on Android TV, which draws its own UI (see FrameAnalyzer).
 *
 * Action: tap YouTube's own Home tab when the tab bar is visible (most reliable on phones),
 * otherwise press Back. If Shorts is still there after a few tries, go to the launcher.
 */
public class ShortsBlockerService extends AccessibilityService {

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL = "protection";
    private static final long HEARTBEAT_EVERY_MS = 30_000;

    private static volatile ShortsBlockerService running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    private Executor workerExecutor;

    private Prefs prefs;
    private volatile Rules rules;
    private FrameAnalyzer analyzer;
    private ScreenSampler sampler;
    private final Object analyzerLock = new Object();

    private long lastActionAt;
    private int actionsInRow;
    private boolean screenshotInFlight;
    private boolean treeCheckPending;
    private boolean guardCheckPending;
    private long lastDiagDumpAt;
    private long lastHeartbeatAt;
    private long lastTreeScanAt;
    private boolean foreground;
    private CharSequence lastFg;
    private long screenBackoffUntil;

    public static boolean isRunning() {
        return running != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = new Prefs(this);
        rules = Rules.load(this);
        synchronized (analyzerLock) {
            analyzer = new FrameAnalyzer(rules);
        }
        workerThread = new HandlerThread("xmart-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        workerExecutor = worker::post;
        running = this;
        prefs.heartbeat();
        DiagLog.log(this, "service: started v" + BuildConfig.VERSION_NAME);

        applyKeepAlive();
        main.postDelayed(loop, 1000);
        worker.postDelayed(maintenance, 30_000);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (running == this) running = null;
        main.removeCallbacksAndMessages(null);
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
            worker.post(() -> {
                if (sampler != null) sampler.close();
                sampler = null;
            });
        }
        if (workerThread != null) workerThread.quitSafely();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------------ keep-alive notification

    static void applyKeepAliveNow() {
        ShortsBlockerService s = running;
        if (s != null) s.main.post(s::applyKeepAlive);
    }

    private void applyKeepAlive() {
        try {
            if (prefs.keepAlive()) {
                Notification n = buildNotification();
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(NOTIF_ID, n);
                }
                foreground = true;
            } else if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foreground = false;
            }
        } catch (Throwable t) {
            // Some devices refuse; the guard still works, it is just easier for the OS to kill.
            DiagLog.log(this, "keep-alive: " + t);
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield_ok)
                .setContentTitle("XMart Blocker is protecting this device")
                .setContentText("Shorts blocked today: " + prefs.blockedToday())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build();
    }

    private void updateNotification() {
        if (!foreground) return;
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (prefs == null || e == null) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        Rules r = rules;

        if (prefs.blocking() && r.isYoutube(pkg) && !treeCheckPending) {
            treeCheckPending = true;
            main.postDelayed(treeCheck, 150);
        }
        if (prefs.settingsGuard() && prefs.hasPin() && r.isSettings(pkg) && !guardCheckPending) {
            guardCheckPending = true;
            main.postDelayed(guardCheck, 250);
        }
    }

    private final Runnable treeCheck = new Runnable() {
        @Override
        public void run() {
            treeCheckPending = false;
            scanTree();
        }
    };

    /** Looks for Shorts in every YouTube window on screen. */
    private void scanTree() {
        lastTreeScanAt = System.currentTimeMillis();
        if (!prefs.blocking()) return;
        for (AccessibilityNodeInfo root : youtubeRoots()) {
            String hit = NodeScanner.findShorts(root, rules);
            if (prefs.diagnostics() && System.currentTimeMillis() - lastDiagDumpAt > 5000) {
                lastDiagDumpAt = System.currentTimeMillis();
                DiagLog.log(this, "tree " + root.getPackageName() + ":\n" + NodeScanner.dump(root, 40));
            }
            if (hit != null) {
                block("tree " + hit, root);
                return;
            }
        }
    }

    private List<AccessibilityNodeInfo> youtubeRoots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null && rules.isYoutube(root.getPackageName())) {
                    if (w.isActive() || w.isFocused()) out.add(0, root);
                    else out.add(root);
                }
            }
        } catch (Exception ignored) {
        }
        if (out.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && rules.isYoutube(root.getPackageName())) out.add(root);
        }
        return out;
    }

    private final Runnable guardCheck = new Runnable() {
        @Override
        public void run() {
            guardCheckPending = false;
            if (prefs.isUnlocked()) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !rules.isSettings(root.getPackageName())) return;
            if (NodeScanner.containsText(root, rules.guardText)) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                toast("Protected by XMart Blocker. Open the app and enter the PIN first.");
                DiagLog.log(ShortsBlockerService.this, "guard: blocked settings page in " + root.getPackageName());
            }
        }
    };

    // ------------------------------------------------------------------ main loop

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            long next;
            try {
                next = tick();
            } catch (Throwable t) {
                DiagLog.log(ShortsBlockerService.this, "loop: error " + t);
                next = 3000;
            }
            main.postDelayed(this, next);
        }
    };

    /** @return delay until the next tick. */
    private long tick() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatAt > HEARTBEAT_EVERY_MS) {
            lastHeartbeatAt = now;
            prefs.heartbeat();
        }
        Rules r = rules;
        if (!prefs.blocking()) return 3000;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return 5000;

        CharSequence pkg = foregroundPackage();
        if (prefs.diagnostics() && pkg != null && !pkg.equals(lastFg)) {
            DiagLog.log(this, "app on screen: " + pkg + (r.isYoutube(pkg)
                    ? (r.needsScreenAnalysis(pkg) ? " (YouTube TV: screen check)" : " (YouTube: tree check)") : ""));
        }
        lastFg = pkg;
        if (!r.isYoutube(pkg)) {
            resetAnalyzer();
            return 1000;
        }

        // Safety net for the tree detector: re-check even if no event arrived.
        if (now - lastTreeScanAt >= r.treeScanIntervalMs && now - lastActionAt > 700) scanTree();

        if (!r.needsScreenAnalysis(pkg) || !prefs.screenDetection()) return r.treeScanIntervalMs;
        long interval = screenInterval(r);
        if (now - lastActionAt < r.cooldownMs) return interval;
        if (screenshotInFlight || now < screenBackoffUntil) return interval;

        screenshotInFlight = true;
        takeScreenshot(Display.DEFAULT_DISPLAY, workerExecutor, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                try {
                    analyse(result);
                } catch (Throwable t) {
                    DiagLog.log(ShortsBlockerService.this, "screen: analyse failed " + t);
                } finally {
                    result.getHardwareBuffer().close();
                    main.post(() -> screenshotInFlight = false);
                }
            }

            @Override
            public void onFailure(int errorCode) {
                main.post(() -> {
                    screenshotInFlight = false;
                    // 3 = asked too soon: wait a little longer next time.
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        screenBackoffUntil = System.currentTimeMillis() + 600;
                    }
                });
                if (prefs.diagnostics()) {
                    DiagLog.log(ShortsBlockerService.this, "screen: screenshot failed code=" + errorCode);
                }
            }
        });
        return interval;
    }

    /** Android 11 allows about one accessibility screenshot per second; later versions about three. */
    private static long screenInterval(Rules r) {
        long min = Build.VERSION.SDK_INT <= 30 ? 1050 : 350;
        return Math.max(r.screenIntervalMs, min);
    }

    /** Recent screen samples kept for the Diagnostics viewer (only while diagnostics are on). */
    static final class Sample {
        final int[] grid;
        final int w, h;
        final String text;
        final long at;

        Sample(int[] grid, int w, int h, String text) {
            this.grid = grid;
            this.w = w;
            this.h = h;
            this.text = text;
            this.at = System.currentTimeMillis();
        }
    }

    private static final java.util.ArrayDeque<Sample> samples = new java.util.ArrayDeque<>();

    static synchronized void addSample(Sample s) {
        samples.addLast(s);
        while (samples.size() > 6) samples.removeFirst();
    }

    static synchronized List<Sample> recentSamples() {
        return new ArrayList<>(samples);
    }

    /** Runs on the worker thread. */
    private void analyse(ScreenshotResult result) {
        Rules r = rules;
        if (sampler == null || sampler.width() != r.gridW || sampler.height() != r.gridH) {
            if (sampler != null) sampler.close();
            sampler = new ScreenSampler(r.gridW, r.gridH);
        }
        int[] grid = sampler.sample(result.getHardwareBuffer(), result.getColorSpace());
        if (grid == null && prefs.diagnostics()) DiagLog.log(this, "screen: could not read screenshot");
        FrameAnalyzer.Result res;
        synchronized (analyzerLock) {
            res = analyzer.analyze(grid);
        }
        if (prefs.diagnostics()) {
            DiagLog.log(this, "screen " + res);
            if (grid != null) addSample(new Sample(grid, r.gridW, r.gridH, res.toString()));
        }
        if (res.shouldBlock) main.post(() -> block("screen " + res, null));
    }

    private void resetAnalyzer() {
        synchronized (analyzerLock) {
            analyzer.reset();
        }
    }

    private CharSequence foregroundPackage() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isFocused() || w.isActive())) {
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root != null) return root.getPackageName();
                }
            }
        } catch (Exception ignored) {
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root == null ? null : root.getPackageName();
    }

    // ------------------------------------------------------------------ action

    private void block(String why, AccessibilityNodeInfo root) {
        if (!prefs.blocking()) return;
        long now = System.currentTimeMillis();
        Rules r = rules;
        if (now - lastActionAt < 700) return; // let the previous action take effect

        actionsInRow = (now - lastActionAt < 5000) ? actionsInRow + 1 : 1;
        lastActionAt = now;
        resetAnalyzer();

        String how;
        if (actionsInRow > r.maxBackPresses + 1) {
            // Nothing else worked several times in a row: leave YouTube.
            performGlobalAction(GLOBAL_ACTION_HOME);
            actionsInRow = 0;
            how = "LAUNCHER";
        } else {
            AccessibilityNodeInfo home = (r.preferHomeTab && root != null && actionsInRow <= 2)
                    ? NodeScanner.findHomeTab(root, r) : null;
            if (home != null && home.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                how = "HOME_TAB";
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK);
                how = "BACK";
            }
        }
        DiagLog.log(this, "block: " + how + " (" + why + ")");
        prefs.recordBlock();
        updateNotification();
        if (prefs.showMessage() && actionsInRow <= 1) toast("Shorts are blocked on this device");

        // Verify shortly after; the tree detector fires again if Shorts is still showing.
        main.postDelayed(this::scanTree, 900);
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------------ maintenance (worker)

    private final Runnable maintenance = new Runnable() {
        @Override
        public void run() {
            try {
                refreshRulesIfStale(false);
                autoUpdateIfPossible();
            } catch (Throwable t) {
                DiagLog.log(ShortsBlockerService.this, "maintenance: " + t);
            }
            worker.postDelayed(this, 60 * 60 * 1000L);
        }
    };

    /** Blocking network call; runs on the worker thread. */
    void refreshRulesIfStale(boolean force) {
        long age = System.currentTimeMillis() - prefs.getLong(Prefs.RULES_FETCHED_AT);
        if (!force && age < rules.rulesRefreshHours * 3600_000L) return;
        try {
            String json = Updater.get("https://raw.githubusercontent.com/" + BuildConfig.UPDATE_REPO + "/main/rules.json");
            Rules fresh = new Rules();
            fresh.apply(new JSONObject(json)); // validates before saving
            prefs.setString(Prefs.RULES_JSON, json);
            prefs.setLong(Prefs.RULES_FETCHED_AT, System.currentTimeMillis());
            rules = fresh;
            synchronized (analyzerLock) {
                analyzer = new FrameAnalyzer(fresh);
            }
            DiagLog.log(this, "rules: updated from GitHub");
        } catch (Exception e) {
            DiagLog.log(this, "rules: refresh failed: " + e.getMessage());
        }
    }

    private void autoUpdateIfPossible() throws Exception {
        long age = System.currentTimeMillis() - prefs.getLong(Prefs.LAST_UPDATE_CHECK);
        if (age < rules.updateCheckHours * 3600_000L) return;
        prefs.setLong(Prefs.LAST_UPDATE_CHECK, System.currentTimeMillis());
        Updater.Release rel = Updater.checkForUpdate();
        if (rel == null) return;
        DiagLog.log(this, "update: " + rel.version + " available");
        if (Updater.canUpdateSilently(this)) {
            File apk = Updater.download(this, rel, null);
            Updater.install(this, apk);
        }
    }

    /** Lets the app screen trigger a rules refresh through the live service, if any. */
    static void refreshRulesNow() {
        ShortsBlockerService s = running;
        if (s != null && s.worker != null) s.worker.post(() -> s.refreshRulesIfStale(true));
    }
}
