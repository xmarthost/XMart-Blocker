package com.xmarthost.blocker;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detection rules. Built-in defaults are compiled in; a newer copy is fetched from rules.json in
 * the GitHub repository, so detection can be tuned without shipping a new APK.
 */
public final class Rules {
    public static final int SCHEMA = 1;

    /** Apps we watch for Shorts. */
    public List<String> youtubePackages = new ArrayList<>(Arrays.asList(
            "com.google.android.youtube.tv",        // YouTube for Android TV / Google TV
            "com.google.android.youtube.googletv",
            "com.google.android.youtube"            // phone/tablet app, also on some TV boxes
    ));

    /** Apps whose UI is drawn without real views (Cobalt), so screen analysis is used. */
    public List<String> screenAnalysisPackages = new ArrayList<>(Arrays.asList(
            "com.google.android.youtube.tv",
            "com.google.android.youtube.googletv"
    ));

    /** View id fragments that only exist on the Shorts player (phone app). */
    public List<String> shortsViewIds = new ArrayList<>(Arrays.asList(
            "reel_player_page_container",
            "reel_recycler",
            "reel_watch_player",
            "reel_player_overlay",
            "reel_progress_bar",
            "reel_time_bar",
            "shorts_player",
            "shorts_container"
    ));

    /** Regexes on text / content description that mark the Shorts player (any app). */
    public List<Pattern> shortsTextPatterns = new ArrayList<>();

    /** Settings / installer apps where the guard stops the child from turning the blocker off. */
    public List<String> settingsPackages = new ArrayList<>(Arrays.asList(
            "com.android.tvsettings",
            "com.google.android.tvsettings",
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller"
    ));
    public String guardText = "XMart Blocker";

    // ---- Screen analysis (see FrameAnalyzer) ----
    public long screenIntervalMs = 700;
    public int gridW = 64;
    public int gridH = 36;
    public int motionThreshold = 14;        // luma change (0..255) that counts as motion
    public double colActive = 0.22;         // share of rows moving for a column to be "active"
    public double minWidth = 0.16;          // allowed width of the moving band (share of screen)
    public double maxWidth = 0.50;
    public double maxCenterOffset = 0.15;   // band centre must be near the screen centre
    public double maxSideMotion = 0.05;     // the sides of the screen must be (nearly) still
    public double minBandMotion = 0.30;     // the band itself must clearly move
    public double minBandHeight = 0.60;     // moving rows must cover most of the height
    public int hitsToBlock = 2;             // consecutive positive windows before acting

    // ---- Actions ----
    public long cooldownMs = 1500;
    public int maxBackPresses = 3;

    public long updateCheckHours = 12;
    public long rulesRefreshHours = 6;

    public static Rules load(Context c) {
        Rules r = new Rules();
        String json = new Prefs(c).getString(Prefs.RULES_JSON);
        if (json != null) {
            try {
                r.apply(new JSONObject(json));
            } catch (Exception e) {
                DiagLog.log(c, "rules: cached rules invalid: " + e);
            }
        }
        return r;
    }

    /** Overwrites only the fields present in the JSON. Unknown schema → ignored. */
    public void apply(JSONObject o) throws Exception {
        if (o.optInt("schema", SCHEMA) > SCHEMA) return;
        youtubePackages = list(o, "youtubePackages", youtubePackages);
        screenAnalysisPackages = list(o, "screenAnalysisPackages", screenAnalysisPackages);
        shortsViewIds = list(o, "shortsViewIds", shortsViewIds);
        settingsPackages = list(o, "settingsPackages", settingsPackages);
        guardText = o.optString("guardText", guardText);
        if (o.has("shortsTextPatterns")) {
            List<Pattern> ps = new ArrayList<>();
            for (String s : list(o, "shortsTextPatterns", new ArrayList<>())) {
                ps.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
            }
            shortsTextPatterns = ps;
        }
        JSONObject a = o.optJSONObject("screen");
        if (a != null) {
            screenIntervalMs = a.optLong("intervalMs", screenIntervalMs);
            gridW = a.optInt("gridW", gridW);
            gridH = a.optInt("gridH", gridH);
            motionThreshold = a.optInt("motionThreshold", motionThreshold);
            colActive = a.optDouble("colActive", colActive);
            minWidth = a.optDouble("minWidth", minWidth);
            maxWidth = a.optDouble("maxWidth", maxWidth);
            maxCenterOffset = a.optDouble("maxCenterOffset", maxCenterOffset);
            maxSideMotion = a.optDouble("maxSideMotion", maxSideMotion);
            minBandMotion = a.optDouble("minBandMotion", minBandMotion);
            minBandHeight = a.optDouble("minBandHeight", minBandHeight);
            hitsToBlock = a.optInt("hitsToBlock", hitsToBlock);
        }
        cooldownMs = o.optLong("cooldownMs", cooldownMs);
        maxBackPresses = o.optInt("maxBackPresses", maxBackPresses);
        updateCheckHours = o.optLong("updateCheckHours", updateCheckHours);
        rulesRefreshHours = o.optLong("rulesRefreshHours", rulesRefreshHours);
    }

    private static List<String> list(JSONObject o, String key, List<String> def) {
        JSONArray arr = o.optJSONArray(key);
        if (arr == null) return def;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        return out;
    }

    public boolean isYoutube(CharSequence pkg) {
        return pkg != null && youtubePackages.contains(pkg.toString());
    }

    public boolean needsScreenAnalysis(CharSequence pkg) {
        return pkg != null && screenAnalysisPackages.contains(pkg.toString());
    }

    public boolean isSettings(CharSequence pkg) {
        return pkg != null && settingsPackages.contains(pkg.toString());
    }
}
