package com.xmarthost.blocker;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;

import java.nio.ByteBuffer;

/**
 * Turns an accessibility screenshot (a GPU HardwareBuffer, often 4K on TVs) into a tiny greyscale
 * grid. The heavy downscale runs on the GPU; the CPU only touches a few thousand pixels.
 * Falls back to a CPU copy if the GPU path is unavailable on a device.
 */
final class ScreenSampler implements AutoCloseable {
    private static final int OVERSAMPLE = 4;

    private final int gw, gh, rw, rh;
    private ImageReader reader;
    private HardwareRenderer renderer;
    private RenderNode node;
    private boolean gpuBroken;

    ScreenSampler(int gridW, int gridH) {
        gw = gridW;
        gh = gridH;
        rw = gridW * OVERSAMPLE;
        rh = gridH * OVERSAMPLE;
    }

    int width() { return gw; }
    int height() { return gh; }

    /** @return luma grid (gw * gh, 0..255) or null. Closes nothing: caller owns the buffer. */
    int[] sample(HardwareBuffer hb, ColorSpace cs) {
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
        if (hw == null) return null;
        try {
            if (!gpuBroken) {
                try {
                    int[] g = sampleGpu(hw);
                    if (g != null) return g;
                } catch (Throwable t) {
                    gpuBroken = true;
                    DiagLog.log(null, "sampler: GPU path failed, using CPU: " + t);
                }
            }
            return sampleCpu(hw);
        } finally {
            hw.recycle();
        }
    }

    private int[] sampleGpu(Bitmap hw) {
        if (renderer == null) {
            reader = ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 2,
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_CPU_READ_OFTEN);
            node = new RenderNode("xmart-sampler");
            node.setPosition(0, 0, rw, rh);
            renderer = new HardwareRenderer();
            renderer.setSurface(reader.getSurface());
            renderer.setContentRoot(node);
        }
        RecordingCanvas c = node.beginRecording(rw, rh);
        try {
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(hw, null, new Rect(0, 0, rw, rh), p);
        } finally {
            node.endRecording();
        }
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();

        Image img = reader.acquireLatestImage();
        if (img == null) return null;
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int px = plane.getPixelStride();
            int[] out = new int[gw * gh];
            for (int gy = 0; gy < gh; gy++) {
                for (int gx = 0; gx < gw; gx++) {
                    int sum = 0;
                    for (int dy = 0; dy < OVERSAMPLE; dy++) {
                        int row = (gy * OVERSAMPLE + dy) * rowStride;
                        for (int dx = 0; dx < OVERSAMPLE; dx++) {
                            int o = row + (gx * OVERSAMPLE + dx) * px;
                            int r = buf.get(o) & 0xff, g = buf.get(o + 1) & 0xff, b = buf.get(o + 2) & 0xff;
                            sum += (r * 299 + g * 587 + b * 114) / 1000;
                        }
                    }
                    out[gy * gw + gx] = sum / (OVERSAMPLE * OVERSAMPLE);
                }
            }
            return out;
        } finally {
            img.close();
        }
    }

    private int[] sampleCpu(Bitmap hw) {
        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
        if (sw == null) return null;
        try {
            int w = sw.getWidth(), h = sw.getHeight();
            int[] out = new int[gw * gh];
            for (int gy = 0; gy < gh; gy++) {
                for (int gx = 0; gx < gw; gx++) {
                    int sum = 0;
                    for (int dy = 0; dy < OVERSAMPLE; dy++) {
                        int y = (int) (((gy * OVERSAMPLE + dy) + 0.5) * h / rh);
                        for (int dx = 0; dx < OVERSAMPLE; dx++) {
                            int x = (int) (((gx * OVERSAMPLE + dx) + 0.5) * w / rw);
                            int c = sw.getPixel(Math.min(x, w - 1), Math.min(y, h - 1));
                            int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                            sum += (r * 299 + g * 587 + b * 114) / 1000;
                        }
                    }
                    out[gy * gw + gx] = sum / (OVERSAMPLE * OVERSAMPLE);
                }
            }
            return out;
        } finally {
            sw.recycle();
        }
    }

    @Override
    public void close() {
        if (renderer != null) renderer.destroy();
        if (node != null) node.discardDisplayList();
        if (reader != null) reader.close();
        renderer = null;
        node = null;
        reader = null;
    }
}
