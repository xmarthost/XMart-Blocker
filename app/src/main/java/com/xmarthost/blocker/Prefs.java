package com.xmarthost.blocker;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Small wrapper around SharedPreferences shared by the app screen and the service. */
public final class Prefs {
    private static final String FILE = "xmart_blocker";

    public static final String BLOCKING = "blocking";
    public static final String SHOW_MESSAGE = "show_message";
    public static final String SCREEN_DETECTION = "screen_detection";
    public static final String SETTINGS_GUARD = "settings_guard";
    public static final String DIAGNOSTICS = "diagnostics";
    public static final String PIN_HASH = "pin_hash";
    public static final String UNLOCKED_UNTIL = "unlocked_until";
    public static final String BLOCK_COUNT = "block_count";
    public static final String LAST_BLOCK_AT = "last_block_at";
    public static final String LAST_UPDATE_CHECK = "last_update_check";
    public static final String RULES_JSON = "rules_json";
    public static final String RULES_FETCHED_AT = "rules_fetched_at";
    public static final String HEARTBEAT = "heartbeat";
    public static final String KEEP_ALIVE = "keep_alive";
    public static final String LAST_CRASH = "last_crash";
    public static final String LAST_CRASH_AT = "last_crash_at";
    public static final String TODAY_KEY = "today_key";
    public static final String TODAY_COUNT = "today_count";
    public static final String NOTIF_ASKED = "notif_asked";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean blocking() { return sp.getBoolean(BLOCKING, true); }
    public boolean showMessage() { return sp.getBoolean(SHOW_MESSAGE, true); }
    public boolean screenDetection() { return sp.getBoolean(SCREEN_DETECTION, true); }
    public boolean settingsGuard() { return sp.getBoolean(SETTINGS_GUARD, true); }
    public boolean diagnostics() { return sp.getBoolean(DIAGNOSTICS, false); }
    public boolean keepAlive() { return sp.getBoolean(KEEP_ALIVE, true); }

    public void setBool(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }
    public boolean getBool(String key, boolean def) { return sp.getBoolean(key, def); }

    public long getLong(String key) { return sp.getLong(key, 0L); }
    public void setLong(String key, long v) { sp.edit().putLong(key, v).apply(); }

    public String getString(String key) { return sp.getString(key, null); }
    public void setString(String key, String v) { sp.edit().putString(key, v).apply(); }

    public boolean hasPin() { return sp.getString(PIN_HASH, null) != null; }

    public void setPin(String pin) {
        if (pin == null || pin.isEmpty()) sp.edit().remove(PIN_HASH).apply();
        else sp.edit().putString(PIN_HASH, hash(pin)).apply();
    }

    public boolean checkPin(String pin) {
        String h = sp.getString(PIN_HASH, null);
        return h == null || h.equals(hash(pin));
    }

    /** While unlocked the settings guard steps aside (parent is changing something). */
    public boolean isUnlocked() { return System.currentTimeMillis() < getLong(UNLOCKED_UNTIL); }
    public void unlockFor(long ms) { setLong(UNLOCKED_UNTIL, System.currentTimeMillis() + ms); }
    public void lockNow() { setLong(UNLOCKED_UNTIL, 0L); }

    public void recordBlock() {
        String today = today();
        long todayCount = today.equals(sp.getString(TODAY_KEY, "")) ? sp.getLong(TODAY_COUNT, 0) : 0;
        sp.edit().putLong(BLOCK_COUNT, getLong(BLOCK_COUNT) + 1)
                .putLong(LAST_BLOCK_AT, System.currentTimeMillis())
                .putString(TODAY_KEY, today)
                .putLong(TODAY_COUNT, todayCount + 1)
                .apply();
    }

    public long blockedToday() {
        return today().equals(sp.getString(TODAY_KEY, "")) ? sp.getLong(TODAY_COUNT, 0) : 0;
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
    }

    public void heartbeat() { setLong(HEARTBEAT, System.currentTimeMillis()); }

    /** Committed synchronously: the process is about to die. */
    public void setCrash(String trace) {
        sp.edit().putString(LAST_CRASH, trace).putLong(LAST_CRASH_AT, System.currentTimeMillis()).commit();
    }

    private static String hash(String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("xmart-blocker:" + pin).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return pin;
        }
    }
}
