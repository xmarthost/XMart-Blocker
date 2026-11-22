package com.xmarthost.blocker;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
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
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The background guard. Android starts it automatically on every boot once it is switched on in
 * Settings → Accessibility, so no launcher or boot receiver is needed.
 *
 * Two detectors run side by side:
 *  1. Accessibility tree (phone YouTube app, and any app that exposes Shorts view ids / text).
 *  2. Screen analysis (YouTube for Android TV draws its own UI, see FrameAnalyzer).
 * When either fires, we press Back once, which leaves the Shorts player and returns to the
 * YouTube page the child came from. YouTube itself keeps running.
 */
public class ShortsBlockerService extends AccessibilityService {

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
    private boolean nodeCheckPending;
    private boolean guardCheckPending;
    private long lastDiagDumpAt;

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
        DiagLog.log(this, "service: started v" + BuildConfig.VERSION_NAME);

        main.postDelayed(screenLoop, 2000);
        worker.postDelayed(maintenance, 30_000);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        running = null;
        main.removeCallbacksAndMessages(null);
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
            worker.post(() -> {
                if (sampler != null) sampler.close();
                sampler = null;
            });
        }
        if (workerThread != null) workerThread.quitSafely();
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------------ events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (prefs == null) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        Rules r = rules;

        if (prefs.blocking() && r.isYoutube(pkg) && !nodeCheckPending) {
            nodeCheckPending = true;
            main.postDelayed(nodeCheck, 250);
        }
        if (prefs.settingsGuard() && prefs.hasPin() && r.isSettings(pkg) && !guardCheckPending) {
            guardCheckPending = true;
            main.postDelayed(guardCheck, 300);
        }
    }

    private final Runnable nodeCheck = new Runnable() {
        @Override
        public void run() {
            nodeCheckPending = false;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !rules.isYoutube(root.getPackageName())) return;
            String hit = NodeScanner.findShorts(root, rules);
            if (prefs.diagnostics() && System.currentTimeMillis() - lastDiagDumpAt > 5000) {
                lastDiagDumpAt = System.currentTimeMillis();
                DiagLog.log(ShortsBlockerService.this, "tree " + root.getPackageName() + ":\n"
                        + NodeScanner.dump(root, 40));
            }
            if (hit != null) block("tree " + hit);
        }
    };

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

    // ------------------------------------------------------------------ screen analysis

    private final Runnable screenLoop = new Runnable() {
        @Override
        public void run() {
            long next = rules.screenIntervalMs;
            try {
                next = tickScreen();
            } catch (Throwable t) {
                DiagLog.log(ShortsBlockerService.this, "screen: error " + t);
                next = 3000;
            }
            main.postDelayed(this, next);
        }
    };

    /** @return delay until the next tick. */
    private long tickScreen() {
        Rules r = rules;
        if (!prefs.blocking() || !prefs.screenDetection()) return 3000;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return 5000;

        CharSequence pkg = foregroundPackage();
        if (!r.needsScreenAnalysis(pkg)) {
            resetAnalyzer();
            return 1200;
        }
        if (System.currentTimeMillis() - lastActionAt < r.cooldownMs) return r.screenIntervalMs;
        if (screenshotInFlight) return r.screenIntervalMs;

        screenshotInFlight = true;
        takeScreenshot(Display.DEFAULT_DISPLAY, workerExecutor, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                try {
                    analyse(result);
                } finally {
                    result.getHardwareBuffer().close();
                    main.post(() -> screenshotInFlight = false);
                }
            }

            @Override
            public void onFailure(int errorCode) {
                main.post(() -> screenshotInFlight = false);
                if (prefs.diagnostics()) {
                    DiagLog.log(ShortsBlockerService.this, "screen: screenshot failed code=" + errorCode);
                }
            }
        });
        return r.screenIntervalMs;
    }

    /** Runs on the worker thread. */
    private void analyse(ScreenshotResult result) {
        Rules r = rules;
        if (sampler == null || sampler.width() != r.gridW || sampler.height() != r.gridH) {
            if (sampler != null) sampler.close();
            sampler = new ScreenSampler(r.gridW, r.gridH);
        }
        int[] grid = sampler.sample(result.getHardwareBuffer(), result.getColorSpace());
        FrameAnalyzer.Result res;
        synchronized (analyzerLock) {
            res = analyzer.analyze(grid);
        }
        if (prefs.diagnostics() && (res.positive || res.bandMotion > 0)) {
            DiagLog.log(this, "screen " + res);
        }
        if (res.shouldBlock) main.post(() -> block("screen " + res));
    }

    private void resetAnalyzer() {
        synchronized (analyzerLock) {
            analyzer.reset();
        }
    }

    private CharSequence foregroundPackage() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) {
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

    private void block(String why) {
        if (!prefs.blocking()) return;
        long now = System.currentTimeMillis();
        Rules r = rules;
        if (now - lastActionAt < 600) return; // de-duplicate detectors firing together

        actionsInRow = (now - lastActionAt < 5000) ? actionsInRow + 1 : 1;
        lastActionAt = now;
        resetAnalyzer();

        if (actionsInRow > r.maxBackPresses) {
            // Back did not leave Shorts several times in a row: go to the TV home screen instead.
            performGlobalAction(GLOBAL_ACTION_HOME);
            actionsInRow = 0;
            DiagLog.log(this, "block: HOME (" + why + ")");
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK);
            DiagLog.log(this, "block: BACK (" + why + ")");
        }
        prefs.recordBlock();
        if (prefs.showMessage()) toast("Shorts are blocked on this TV");
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

    /** Called from the app screen too (via static helper). Blocking network call. */
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
