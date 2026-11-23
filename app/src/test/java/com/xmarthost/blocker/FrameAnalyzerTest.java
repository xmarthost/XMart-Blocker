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

    // ---- black-hole detector: video layer invisible to screenshots ----

    /** YouTube-dark background (16) with white text/icons; [x0,x1) is a pure-black video hole. */
    private int[] uiFrame(double x0, double x1, boolean fullBlack) {
        int w = rules.gridW, h = rules.gridH;
        int[] f = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean hole = fullBlack || (x >= x0 * w && x < x1 * w && y >= 0.03 * h && y < 0.97 * h);
                int v = hole ? 1 : 16;
                // channel name / title on the left, like & share buttons on the right
                if (!hole && ((x >= 3 && x <= 12 && y >= 26 && y <= 28) || (x >= 46 && x <= 48 && y % 5 == 0 && y > 8 && y < 30))) v = 210;
                f[y * w + x] = v;
            }
        }
        if (fullBlack) {
            for (int x = 4; x < w - 4; x++) f[(h - 2) * w + x] = 180; // seek bar of a paused normal video
        }
        return f;
    }

    private boolean staticBlocks(int frames, int[] frame) {
        FrameAnalyzer a = new FrameAnalyzer(rules);
        for (int i = 0; i < frames; i++) {
            if (a.analyze(frame.clone()).shouldBlock) return true;
        }
        return false;
    }

    @Test public void invisibleVerticalVideoWithUiOnSidesIsBlocked() {
        assertTrue(staticBlocks(4, uiFrame(0.34, 0.66, false)));
    }

    @Test public void invisibleFullScreenVideoIsAllowed() {
        assertFalse(staticBlocks(8, uiFrame(0, 0, true)));
    }

    @Test public void darkHomeScreenIsAllowed() {
        // YouTube dark background with thumbnails in a grid: no pure-black column.
        int w = rules.gridW, h = rules.gridH;
        int[] f = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                f[y * w + x] = ((x / 16) % 2 == 0 && y > 10 && y < 30) ? 120 : 16;
        assertFalse(staticBlocks(8, f));
    }

    // ---- blurred moving backdrop (YouTube TV ambient background) ----

    /** Sharp video in [0.34,0.66) changing a lot; sides drift slowly (blurred copy of the video). */
    private int[] ambientFrame(int t) {
        int w = rules.gridW, h = rules.gridH;
        int[] f = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean video = x >= 0.34 * w && x < 0.66 * w;
                f[y * w + x] = video ? rnd.nextInt(256) : 60 + ((t * 7 + x + y) % 30); // drift of up to ~30
            }
        }
        return f;
    }

    @Test public void shortWithBlurredMovingBackdropIsBlocked() {
        FrameAnalyzer a = new FrameAnalyzer(rules);
        boolean blocked = false;
        for (int t = 0; t < 6 && !blocked; t++) blocked = a.analyze(ambientFrame(t)).shouldBlock;
        assertTrue(blocked);
    }

    @Test public void fastFullScreenVideoStillAllowed() {
        // Strong motion everywhere (action scene) must not look like a Short.
        assertFalse(blocksWithin(10, 0.0, 1.0, 0.0, 1.0));
    }

    @Test public void alertModeBlocksOnFirstPositiveFrame() {
        FrameAnalyzer a = new FrameAnalyzer(rules);
        a.markBlocked();
        a.reset();
        int[] hole = uiFrame(0.34, 0.66, false);
        assertTrue(a.analyze(hole).shouldBlock);
    }

    @Test public void withoutAlertOneFrameIsNotEnough() {
        FrameAnalyzer a = new FrameAnalyzer(rules);
        assertFalse(a.analyze(uiFrame(0.34, 0.66, false)).shouldBlock);
    }
}
