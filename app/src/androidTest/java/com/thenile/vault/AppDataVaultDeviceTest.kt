package com.thenile.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thenile.vault.root.AppDataVault
import com.thenile.vault.state.VaultStateManager
import com.topjohnwu.superuser.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device end-to-end for AppDataVault: real tar + pm clear + chown + restorecon against a real
 *  (harmless, throwaway-data) package. Needs a rooted device with root granted to the app. */
@RunWith(AndroidJUnit4::class)
class AppDataVaultDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val pkg = "com.android.egg" // Easter Egg app: harmless, always preinstalled, disposable data.
    private val userId = 0

    @Test
    fun snapshotWipeAndRestoreRoundTrip() {
        assumeTrue("needs root", Shell.getShell().isRoot)
        val salt = VaultStateManager.getInstance(ctx).keySalt()
        // Root-only-writable destination (like a real HiddenVolume mount point: root:root, app can't
        // create files there directly) — exercises the scratch-dir staging path for real.
        val encDest = "/data/local/tmp/adv_egg_snapshot.enc"
        val markerPath = "/data/user/$userId/$pkg/files/adv_marker.txt"
        val scratch = ctx.cacheDir.path

        try {
            // Plant a known file inside the target app's data, owned by the app's own uid — matching
            // real conditions, since installd's pm-clear skips files it doesn't recognize as the
            // app's own (root-owned files created directly by a root shell get silently left behind).
            val eggUid = Shell.cmd("stat -c %u /data/user/$userId/$pkg").exec().out.firstOrNull()
            assertTrue("planting marker should succeed",
                Shell.cmd("mkdir -p /data/user/$userId/$pkg/files",
                          "echo -n MARKER-CONTENT > $markerPath",
                          "chown $eggUid:$eggUid /data/user/$userId/$pkg/files $markerPath",
                          "restorecon -R /data/user/$userId/$pkg/files").exec().isSuccess)

            assertTrue("snapshot should succeed",
                AppDataVault.snapshot(pkg, userId, "8888", salt, encDest, wipeAfter = true, scratch))

            // wipeAfter=true must leave nothing real behind — see the rm -rf backstop in
            // AppDataVault.snapshot(), since pm clear's own "Success" isn't synchronous with the
            // actual unlink (confirmed on-device under Magisk root).
            assertNull("data should be wiped after snapshot",
                Shell.cmd("test -f $markerPath && echo yes").exec().out.firstOrNull())

            assertTrue("restore should succeed", AppDataVault.restore(pkg, userId, "8888", salt, encDest, scratch))

            val restored = Shell.cmd("cat $markerPath").exec().out.firstOrNull()
            assertEquals("MARKER-CONTENT", restored)
        } finally {
            Shell.cmd("rm -f $encDest").exec()
        }
    }
}
