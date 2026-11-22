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
 * Pure Java so it can be unit tested on the JVM.
 */
public final class FrameAnalyzer {

    public static final class Result {
        public boolean positive;
        public boolean shouldBlock;
        public int hits;
        public double width, centerOffset, bandMotion, sideMotion, bandHeight;
        public String reason = "";

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "%s w=%.2f off=%.2f band=%.2f side=%.2f h=%.2f hits=%d %s",
                    positive ? "SHORT?" : "-", width, centerOffset, bandMotion, sideMotion,
                    bandHeight, hits, reason);
        }
    }

    private static final int HISTORY = 3;

    private final Rules rules;
    private int[] prev;
    private final ArrayDeque<boolean[]> history = new ArrayDeque<>();
    private int hits;

    public FrameAnalyzer(Rules rules) {
        this.rules = rules;
    }

    public void reset() {
        prev = null;
        history.clear();
        hits = 0;
    }

    /** @param luma grid of rules.gridW x rules.gridH brightness values 0..255, row-major. */
    public Result analyze(int[] luma) {
        final int w = rules.gridW, h = rules.gridH;
        Result res = new Result();
        if (luma == null || luma.length != w * h) {
            res.reason = "bad-frame";
            reset();
            return res;
        }
        if (prev == null) {
            prev = luma.clone();
            res.reason = "warming-up";
            return res;
        }

        boolean[] mask = new boolean[w * h];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = Math.abs(luma[i] - prev[i]) > rules.motionThreshold;
        }
        prev = luma.clone();
        history.addLast(mask);
        while (history.size() > HISTORY) history.removeFirst();

        // Motion seen in any of the recent sample pairs.
        boolean[] moving = new boolean[w * h];
        for (boolean[] m : history) {
            for (int i = 0; i < moving.length; i++) moving[i] |= m[i];
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
        } else {
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
            else res.positive = true;
        }

        hits = res.positive ? hits + 1 : 0;
        res.hits = hits;
        res.shouldBlock = hits >= rules.hitsToBlock;
        return res;
    }
}
