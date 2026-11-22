package com.xmarthost.blocker;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-updater backed by GitHub Releases of BuildConfig.UPDATE_REPO.
 * A release tagged v1.2.3 with an .apk asset is offered when it is newer than this build.
 * All methods block: call them off the main thread.
 */
public final class Updater {

    public static final class Release {
        public String version;
        public int versionCode;
        public String apkUrl;
        public String notes;
    }

    public interface Progress {
        void onProgress(int percent);
    }

    private Updater() {}

    public static int versionCode(String version) {
        String v = version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
        String[] p = v.split("\\.");
        int[] n = new int[3];
        for (int i = 0; i < 3 && i < p.length; i++) {
            String digits = p[i].replaceAll("[^0-9]", "");
            n[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
        return n[0] * 10000 + n[1] * 100 + n[2];
    }

    /** @return the latest release if it is newer than the running app, else null. */
    public static Release checkForUpdate() throws Exception {
        String body = get("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest");
        JSONObject o = new JSONObject(body);
        Release r = new Release();
        r.version = o.getString("tag_name");
        r.versionCode = versionCode(r.version);
        r.notes = o.optString("body", "");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) {
                    r.apkUrl = a.getString("browser_download_url");
                    break;
                }
            }
        }
        if (r.apkUrl == null || r.versionCode <= BuildConfig.VERSION_CODE) return null;
        return r;
    }

    public static File download(Context c, Release r, Progress p) throws Exception {
        File out = new File(c.getCacheDir(), "update.apk");
        HttpURLConnection con = open(r.apkUrl);
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new Exception("Download failed: HTTP " + code);
            long total = con.getContentLengthLong();
            try (InputStream in = con.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (p != null && total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != last) p.onProgress(last = pct);
                    }
                }
            }
        } finally {
            con.disconnect();
        }
        return out;
    }

    /**
     * Installs the APK through PackageInstaller. Android shows one confirmation the first time;
     * once this app is the "installer of record" (Android 12+), later updates install silently.
     */
    public static void install(Context c, File apk) throws Exception {
        // The settings guard must not block the system install screen we are about to open.
        new Prefs(c).unlockFor(3 * 60 * 1000L);

        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream os = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                s.fsync(os);
            }
            Intent i = new Intent(c, InstallReceiver.class);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pend.getIntentSender());
        }
    }

    /** True when a previous self-update made this app its own installer (silent updates work). */
    public static boolean canUpdateSilently(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            InstallSourceInfo info = c.getPackageManager().getInstallSourceInfo(c.getPackageName());
            return c.getPackageName().equals(info.getInstallingPackageName())
                    && c.getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return false;
        }
    }

    static String get(String url) throws Exception {
        HttpURLConnection con = open(url);
        try {
            int code = con.getResponseCode();
            if (code == 404) throw new Exception("No release published yet");
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            }
        } finally {
            con.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "XMart-Blocker/" + BuildConfig.VERSION_NAME);
        con.setRequestProperty("Accept", url.contains("api.github.com")
                ? "application/vnd.github+json" : "*/*");
        return con;
    }
}
