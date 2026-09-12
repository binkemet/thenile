package com.thenile.vault.root

import android.util.Log
import com.topjohnwu.superuser.Shell

class TraceCleaner {
    companion object {
        private const val TAG = "TraceCleaner"

        fun cleanTraces(targetPackage: String) {
            cleanAllTraces(listOf(targetPackage))
        }

        fun cleanAllTraces(
            packages: List<String> = emptyList(),
            directories: List<String> = emptyList(),
            files: List<String> = emptyList()
        ) {
            SecureLog.d(TAG, "Scrubbing forensic traces safely (packages=${packages.size}, dirs=${directories.size}, files=${files.size})")

            val cmdList = mutableListOf<String>()

            // 1. Force stop target packages
            for (pkg in packages) {
                cmdList.add("am force-stop '$pkg'")
            }

            // 2. Wipe snapshot images & recent thumbnails (Safe for system_server)
            cmdList.add("rm -rf /data/system_ce/0/snapshots/* /data/system_ce/0/recent_images/* /data/system/recent_images/* 2>/dev/null || true")

            // 3. Scrub MediaStore SQLite Databases
            val mediaDbs = listOf(
                "/data/data/com.android.providers.media.module/databases/external.db",
                "/data/data/com.android.providers.media/databases/external.db",
                "/data/user/0/com.android.providers.media.module/databases/external.db",
                "/data/user/0/com.android.providers.media/databases/external.db"
            )

            val targets = (directories + files).distinct()
            if (targets.isNotEmpty()) {
                val sqlQueries = mutableListOf<String>()
                for (t in targets) {
                    val escaped = t.replace("'", "''")
                    val mediaPath = escaped.replace("/sdcard/", "/data/media/0/").replace("/storage/emulated/0/", "/data/media/0/")
                    sqlQueries.add("DELETE FROM files WHERE _data LIKE '%$escaped%' OR _data LIKE '%$mediaPath%';")
                    sqlQueries.add("DELETE FROM images WHERE _data LIKE '%$escaped%' OR _data LIKE '%$mediaPath%';")
                    sqlQueries.add("DELETE FROM video WHERE _data LIKE '%$escaped%' OR _data LIKE '%$mediaPath%';")
                    sqlQueries.add("DELETE FROM audio WHERE _data LIKE '%$escaped%' OR _data LIKE '%$mediaPath%';")
                }
                sqlQueries.add("DELETE FROM thumbnails WHERE image_id NOT IN (SELECT _id FROM images);")

                val combinedSql = sqlQueries.joinToString(" ")
                for (db in mediaDbs) {
                    cmdList.add("[ -f '$db' ] && sqlite3 '$db' \"$combinedSql\" 2>/dev/null || true")
                }
            }

            // 4. Purge Thumbnail & Media Caches (Safe user caches)
            cmdList.add("rm -rf /data/media/0/.thumbnails/* /data/media/0/DCIM/.thumbnails/* /sdcard/.thumbnails/* /sdcard/DCIM/.thumbnails/* 2>/dev/null || true")
            cmdList.add("rm -rf /data/data/com.android.providers.media.module/cache/* /data/data/com.android.providers.media/cache/* 2>/dev/null || true")
            cmdList.add("rm -rf /data/data/com.google.android.apps.photos/cache/* 2>/dev/null || true")

            // 5. Clear in-memory logcat buffers
            cmdList.add("logcat -c 2>/dev/null || true")

            // 6. Flush Linux page cache and drop dirty kernel buffers
            cmdList.add("sync; echo 3 > /proc/sys/vm/drop_caches")

            Shell.cmd(*cmdList.toTypedArray()).exec()
            SecureLog.d(TAG, "Trace scrubbing complete")
        }
    }
}
