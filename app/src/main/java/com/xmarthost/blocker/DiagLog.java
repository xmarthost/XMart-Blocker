package com.xmarthost.blocker;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * In-memory ring buffer of recent events, mirrored to files/diag.txt when diagnostics are on.
 * The file can be pulled with: adb shell run-as is not available for release builds, so the
 * app shows the log on screen instead (Diagnostics button).
 */
public final class DiagLog {
    private static final String TAG = "XMartBlocker";
    private static final int MAX = 200;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private DiagLog() {}

    public static synchronized void log(Context c, String msg) {
        String line = fmt.format(new Date()) + " " + msg;
        Log.i(TAG, msg);
        lines.addLast(line);
        while (lines.size() > MAX) lines.removeFirst();
        if (c != null && new Prefs(c).diagnostics()) {
            try (FileWriter w = new FileWriter(new File(c.getFilesDir(), "diag.txt"), true)) {
                w.write(line);
                w.write('\n');
            } catch (Exception ignored) {
            }
        }
    }

    public static synchronized String recent(int n) {
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, lines.size() - n);
        int i = 0;
        for (String l : lines) {
            if (i++ < skip) continue;
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear(Context c) {
        lines.clear();
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), "diag.txt").delete();
    }
}
