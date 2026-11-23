package com.xmarthost.blocker;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Decides from screenshots whether a vertical (Shorts-style) video is playing.
 *
 * The YouTube Android TV app draws everything itself (Cobalt), so accessibility nodes carry no
 * text. Instead we look at small, greyscale screenshots: a Short plays as a tall 9:16 video in the
 * middle of a 16:9 TV, so motion is confined to a narrow centred band that runs top to bottom,
 * while the left and right parts of the screen stay still. Normal videos move across the full
 * width, and browsing screens either do not move or move everywhere at once (scrolling).
 *
 * Second signal ("black hole"): on many TVs the video plays on a hardware layer that screenshots
 * cannot see, so the video area is pure black. A Short then shows up as a tall, pure-black,
 * centred column with YouTube's (non-black) interface on both sides. A normal full-screen video is
 * black everywhere, so it does not match.
 *
 * Pure Java so it can be unit tested on the JVM.
 */
public final class FrameAnalyzer {

    public static final class Result {
        public boolean positive;
        public boolean shouldBlock;
        public int hits;
        public double width, centerOffset, bandMotion, sideMotion, bandHeight;
        /** Black-hole detector: mean brightness of the centre band and of the sides. */
        public double holeLuma = -1, sideLuma = -1, sideTexture = -1;
        public String method = "";
        public String reason = "";

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "%s%s w=%.2f off=%.2f band=%.2f side=%.2f h=%.2f hole=%.0f sideL=%.0f tex=%.3f hits=%d %s",
                    positive ? "SHORT? " : "- ", method, width, centerOffset, bandMotion, sideMotion,
                    bandHeight, holeLuma, sideLuma, sideTexture, hits, reason);
        }
    }

    private static final int HISTORY = 3;

    private final Rules rules;
    private int[] prev;
    private final ArrayDeque<int[]> diffs = new ArrayDeque<>();
    private int hits;
    /** Frames left in "alert" mode after a block: a single positive frame is then enough. */
    private int alertFrames;

    public FrameAnalyzer(Rules rules) {
        this.rules = rules;
    }

    /** Clears motion history (after an action or when YouTube leaves the screen). Keeps alert mode. */
    public void reset() {
        prev = null;
        diffs.clear();
        hits = 0;
    }

    /** Call after blocking a Short: children usually scroll straight to the next one. */
    public void markBlocked() {
        alertFrames = rules.alertFrames;
    }

    public boolean inAlert() {
        return alertFrames > 0;
    }

    /** @param luma grid of rules.gridW x rules.gridH brightness values 0..255, row-major. */
    public Result analyze(int[] luma) {
        final int w = rules.gridW, h = rules.gridH;
        if (alertFrames > 0) alertFrames--;
        Result res = new Result();
        if (luma == null || luma.length != w * h) {
            res.reason = "bad-frame";
            reset();
            return res;
        }
        if (prev == null) {
            prev = luma.clone();
            res.reason = "warming-up";
        } else {
            int[] d = new int[w * h];
            for (int i = 0; i < d.length; i++) d[i] = Math.abs(luma[i] - prev[i]);
            prev = luma.clone();
            diffs.addLast(d);
            while (diffs.size() > HISTORY) diffs.removeFirst();

            motion(rules.motionThreshold, "motion", res);
            // Strong-motion pass: YouTube TV shows a blurred, slowly moving copy of the Short behind it.
            // Only the sharp video itself changes a lot between samples.
            if (!res.positive && rules.strongMotionThreshold > 0) {
                Result strong = new Result();
                motion(rules.strongMotionThreshold, "strong-motion", strong);
                if (strong.positive) res = strong;
            }
        }
        if (!res.positive) applyHole(luma, res);

        hits = res.positive ? hits + 1 : 0;
        res.hits = hits;
        int needed = alertFrames > 0 ? 1 : rules.hitsToBlock;
        res.shouldBlock = hits >= needed;
        if (alertFrames > 0) res.reason = (res.reason + " alert").trim();
        return res;
    }

    /** Looks for motion confined to a tall centred band, counting changes above threshold. */
    private void motion(int threshold, String method, Result res) {
        final int w = rules.gridW, h = rules.gridH;
        boolean[] moving = new boolean[w * h];
        for (int[] d : diffs) {
            for (int i = 0; i < moving.length; i++) moving[i] |= d[i] > threshold;
        }

        double[] col = new double[w];
        for (int x = 0; x < w; x++) {
            int n = 0;
            for (int y = 0; y < h; y++) if (moving[y * w + x]) n++;
            col[x] = n / (double) h;
        }

        // Longest run of active columns, tolerating single-column gaps.
        int bestA = -1, bestB = -1, a = -1, lastActive = -1;
        for (int x = 0; x < w; x++) {
            if (col[x] >= rules.colActive) {
                if (a < 0 || x - lastActive > 2) a = x;
                lastActive = x;
                if (bestA < 0 || (x - a) > (bestB - bestA)) {
                    bestA = a;
                    bestB = x;
                }
            }
        }

        if (bestA < 0) {
            res.reason = "no-motion";
            return;
        }
        res.width = (bestB - bestA + 1) / (double) w;
        res.centerOffset = Math.abs((bestA + bestB + 1) / 2.0 / w - 0.5);

        double band = 0;
        for (int x = bestA; x <= bestB; x++) band += col[x];
        res.bandMotion = band / (bestB - bestA + 1);

        double side = 0;
        int sideCols = 0;
        for (int x = 0; x < w; x++) {
            if (x < bestA - 1 || x > bestB + 1) {
                side += col[x];
                sideCols++;
            }
        }
        res.sideMotion = sideCols == 0 ? 1.0 : side / sideCols;

        int first = -1, last = -1;
        int bandCols = bestB - bestA + 1;
        for (int y = 0; y < h; y++) {
            int n = 0;
            for (int x = bestA; x <= bestB; x++) if (moving[y * w + x]) n++;
            if (n >= Math.max(1, bandCols / 5)) {
                if (first < 0) first = y;
                last = y;
            }
        }
        res.bandHeight = first < 0 ? 0 : (last - first + 1) / (double) h;

        if (res.width < rules.minWidth) res.reason = "too-narrow";
        else if (res.width > rules.maxWidth) res.reason = "too-wide";
        else if (res.centerOffset > rules.maxCenterOffset) res.reason = "off-centre";
        else if (res.sideMotion > rules.maxSideMotion) res.reason = "sides-moving";
        else if (res.bandMotion < rules.minBandMotion) res.reason = "weak-motion";
        else if (res.bandHeight < rules.minBandHeight) res.reason = "too-short";
        else {
            res.positive = true;
            res.method = method;
            res.reason = "";
        }
    }

    /** Marks res positive when the frame shows a centred pure-black column with UI on both sides. */
    private void applyHole(int[] luma, Result res) {
        if (!rules.holeDetection) return;
        final int w = rules.gridW, h = rules.gridH;
        int y0 = (int) Math.round(h * rules.holeRowFrom), y1 = (int) Math.round(h * rules.holeRowTo);
        if (y1 - y0 < 4) return;

        boolean[] black = new boolean[w];
        double[] mean = new double[w];
        for (int x = 0; x < w; x++) {
            double sum = 0, sq = 0;
            int max = 0;
            for (int y = y0; y < y1; y++) {
                int v = luma[y * w + x];
                sum += v;
                sq += (double) v * v;
                if (v > max) max = v;
            }
            int n = y1 - y0;
            mean[x] = sum / n;
            double sd = Math.sqrt(Math.max(0, sq / n - mean[x] * mean[x]));
            black[x] = mean[x] <= rules.holeMaxLuma && sd <= rules.holeMaxStd && max <= rules.holeMaxLuma * 3;
        }

        // Longest run of black columns (single-column gaps allowed).
        int bestA = -1, bestB = -1, a = -1, last = -1;
        for (int x = 0; x < w; x++) {
            if (black[x]) {
                if (a < 0 || x - last > 2) a = x;
                last = x;
                if (bestA < 0 || (x - a) > (bestB - bestA)) {
                    bestA = a;
                    bestB = x;
                }
            }
        }
        if (bestA < 0) return;
        double width = (bestB - bestA + 1) / (double) w;
        double off = Math.abs((bestA + bestB + 1) / 2.0 / w - 0.5);

        double holeSum = 0;
        for (int x = bestA; x <= bestB; x++) holeSum += mean[x];
        double holeLuma = holeSum / (bestB - bestA + 1);

        // Sides: brightness and "texture" (cells clearly brighter than the black hole: text, buttons).
        double sideSum = 0;
        int sideCells = 0, bright = 0, leftBright = 0, rightBright = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= bestA - 1 && x <= bestB + 1) continue;
                int v = luma[y * w + x];
                sideSum += v;
                sideCells++;
                if (v >= rules.holeTextureLuma) {
                    bright++;
                    if (x < bestA) leftBright++; else rightBright++;
                }
            }
        }
        if (sideCells == 0) return;
        double sideLuma = sideSum / sideCells;
        double texture = bright / (double) sideCells;

        res.holeLuma = holeLuma;
        res.sideLuma = sideLuma;
        res.sideTexture = texture;
        if (width < rules.minWidth || width > rules.maxWidth) return;
        if (off > rules.maxCenterOffset) return;
        if (sideLuma < holeLuma + rules.holeMinSideContrast) return;
        if (texture < rules.holeMinSideTexture || leftBright + rightBright == 0) return;

        res.positive = true;
        res.method = "black-hole";
        res.width = width;
        res.centerOffset = off;
        res.reason = "";
    }
}
