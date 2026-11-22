package com.xmarthost.blocker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class FrameAnalyzerTest {
    private final Rules rules = new Rules();
    private final Random rnd = new Random(42);

    /** Static grey frame with random "video" pixels in columns [x0, x1) and rows [y0, y1). */
    private int[] frame(double x0, double x1, double y0, double y1) {
        int w = rules.gridW, h = rules.gridH;
        int[] f = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean video = x >= x0 * w && x < x1 * w && y >= y0 * h && y < y1 * h;
                f[y * w + x] = video ? rnd.nextInt(256) : 40 + (x % 7) * 10;
            }
        }
        return f;
    }

    private boolean blocksWithin(int frames, double x0, double x1, double y0, double y1) {
        FrameAnalyzer a = new FrameAnalyzer(rules);
        for (int i = 0; i < frames; i++) {
            if (a.analyze(frame(x0, x1, y0, y1)).shouldBlock) return true;
        }
        return false;
    }

    @Test public void verticalVideoInCentreIsBlocked() {
        // 9:16 video on a 16:9 screen is ~32% of the width.
        assertTrue(blocksWithin(5, 0.34, 0.66, 0.0, 1.0));
    }

    @Test public void shortWithSmallMarginsIsBlocked() {
        assertTrue(blocksWithin(5, 0.35, 0.65, 0.04, 0.96));
    }

    @Test public void fullScreenVideoIsAllowed() {
        assertFalse(blocksWithin(10, 0.0, 1.0, 0.0, 1.0));
    }

    @Test public void letterboxedWideVideoIsAllowed() {
        assertFalse(blocksWithin(10, 0.0, 1.0, 0.12, 0.88));
    }

    @Test public void staticScreenIsAllowed() {
        assertFalse(blocksWithin(10, 0, 0, 0, 0));
    }

    @Test public void smallPreviewTileIsAllowed() {
        // A thumbnail preview playing in a home-screen row (short and off centre).
        assertFalse(blocksWithin(10, 0.05, 0.25, 0.55, 0.85));
    }

    @Test public void topBannerTrailerIsAllowed() {
        assertFalse(blocksWithin(10, 0.40, 1.0, 0.0, 0.5));
    }
}
