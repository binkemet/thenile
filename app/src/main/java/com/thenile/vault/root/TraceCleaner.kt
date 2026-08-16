package com.thenile.vault.root

import android.util.Log
import com.topjohnwu.superuser.Shell

class TraceCleaner {
    companion object {
        private const val TAG = "TraceCleaner"

        fun cleanTraces(targetPackage: String) {
            Log.d(TAG, "Cleaning traces for $targetPackage")
            Shell.cmd("am force-stop $targetPackage").exec()
            Shell.cmd("rm -rf /data/system/recent_images/*").exec()
            Shell.cmd("rm -rf /data/system_ce/0/snapshots/*").exec()
            Shell.cmd("rm -rf /data/system/usagestats/*").exec()
            Shell.cmd("rm -f /data/system/package-usage.list").exec()
            Shell.cmd("rm -f /data/system/package-dex-usage.list").exec()
            Shell.cmd("rm -f /data/system/package-cstats.list").exec()
            Shell.cmd("rm -rf /data/system/graphicsstats/*").exec()
            // Flush page cache
            Shell.cmd("sync; echo 1 > /proc/sys/vm/drop_caches").exec()
        }
    }
}
