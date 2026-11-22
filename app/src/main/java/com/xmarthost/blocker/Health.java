package com.xmarthost.blocker;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;
import java.util.Locale;

/** Works out whether protection is really running, and how to keep it running. */
public final class Health {

    public enum State { ON, STOPPED, OFF }

    private static final long HEARTBEAT_STALE_MS = 2 * 60 * 1000L;

    private Health() {}

    public static ComponentName component(Context c) {
        return new ComponentName(c, ShortsBlockerService.class);
    }

    /** Switched on in Settings → Accessibility. */
    public static boolean enabledInSettings(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName me = component(c);
        for (String s : list.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    /** Android currently has the service bound (it is not crashed or killed). */
    public static boolean bound(Context c) {
        AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String pkg = c.getPackageName();
        String cls = ShortsBlockerService.class.getName();
        for (AccessibilityServiceInfo i : list) {
            if (i.getResolveInfo() != null && i.getResolveInfo().serviceInfo != null
                    && pkg.equals(i.getResolveInfo().serviceInfo.packageName)
                    && cls.equals(i.getResolveInfo().serviceInfo.name)) {
                return true;
            }
        }
        return false;
    }

    public static State state(Context c) {
        if (!enabledInSettings(c)) return State.OFF;
        if (ShortsBlockerService.isRunning()) return State.ON;
        long beat = new Prefs(c).getLong(Prefs.HEARTBEAT);
        boolean fresh = System.currentTimeMillis() - beat < HEARTBEAT_STALE_MS;
        return bound(c) && fresh ? State.ON : State.STOPPED;
    }

    public static boolean isTv(Context c) {
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    // ------------------------------------------------------------------ battery

    public static boolean batteryUnrestricted(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    public static Intent batteryIntent(Context c) {
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + c.getPackageName()));
    }

    // ------------------------------------------------------------------ vendor autostart

    /** Phone brands whose battery savers stop accessibility services unless the app may autostart. */
    public static boolean needsAutostart() {
        String m = Build.MANUFACTURER.toLowerCase(Locale.US);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") || m.contains("oppo")
                || m.contains("realme") || m.contains("vivo") || m.contains("iqoo") || m.contains("oneplus")
                || m.contains("huawei") || m.contains("honor") || m.contains("infinix") || m.contains("tecno")
                || m.contains("itel") || m.contains("asus") || m.contains("letv");
    }

    /** Candidate screens, tried in order; the first one that opens wins. */
    public static Intent[] autostartIntents(Context c) {
        return new Intent[]{
                cmp("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                cmp("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                cmp("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                cmp("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                cmp("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                cmp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                cmp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                cmp("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                cmp("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                cmp("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                cmp("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
                cmp("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
                cmp("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName())),
        };
    }

    private static Intent cmp(String pkg, String cls) {
        return new Intent().setComponent(new ComponentName(pkg, cls));
    }
}
