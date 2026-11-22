package com.xmarthost.blocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Receives PackageInstaller results; opens the system confirmation screen when Android asks. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = android.os.Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : legacyIntentExtra(intent);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                    } catch (Exception e) {
                        DiagLog.log(c, "update: cannot open confirm screen: " + e);
                    }
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                DiagLog.log(c, "update: installed");
                break;
            default:
                DiagLog.log(c, "update: failed " + status + " " + msg);
                Toast.makeText(c, "XMart Blocker update failed: " + msg, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent legacyIntentExtra(Intent intent) {
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }
}
