package com.thenile.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thenile.vault.root.HiddenAppManager
import com.thenile.vault.root.HiddenVolume
import com.thenile.vault.root.StorageMountManager
import com.thenile.vault.state.Vault
import com.thenile.vault.state.VaultStateManager
import com.topjohnwu.superuser.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device end-to-end for the actual decoy/real trigger cycle (HiddenAppManager), never
 *  exercised before — earlier tests only checked AppDataVault/HiddenVolume directly.
 *  captureDecoy/captureReal set up the two snapshots exactly like the "Capture decoy"/"Capture
 *  real" admin buttons; showDecoy/revealReal are what actually run when a decoy/real PIN is
 *  entered. NS = nsenter -t 1 -m -- for every touch of another app's data — see
 *  AppDataVaultAfterMountTest for why. */
@RunWith(AndroidJUnit4::class)
class HiddenAppManagerDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val pkg = "com.android.egg"
    private val userId = 0
    private val NS = "nsenter -t 1 -m --"
    private val markerPath = "/data/user/$userId/$pkg/files/adv_marker.txt"

    private fun writeMarker(content: String) {
        val eggUid = Shell.cmd("$NS stat -c %u /data/user/$userId/$pkg").exec().out.firstOrNull()
        assertTrue("writing marker ($content) should succeed",
            Shell.cmd("$NS mkdir -p /data/user/$userId/$pkg/files",
                      "$NS sh -c \"echo -n $content > $markerPath\"",
                      "$NS chown $eggUid:$eggUid /data/user/$userId/$pkg/files $markerPath",
                      "$NS restorecon -R /data/user/$userId/$pkg/files").exec().isSuccess)
    }

    private fun readMarker(): String? = Shell.cmd("$NS cat $markerPath").exec().out.firstOrNull()

    @Test
    fun decoyAndRealSwapCycle() {
        assumeTrue("needs root", Shell.getShell().isRoot)
        StorageMountManager.dmcryptBin = "${ctx.applicationInfo.nativeLibraryDir}/libdmcrypt.so"
        assumeTrue("dm-crypt helper must ship",
            Shell.cmd("test -x ${StorageMountManager.dmcryptBin}").exec().isSuccess)

        val vault = Vault(id = "test", name = "Test", hiddenApps = listOf(pkg))

        try {
            // 1. Set up the anodyne state and capture it as the decoy snapshot (no wipe).
            writeMarker("ANODYNE-DATA")
            assertTrue("captureDecoy should succeed", HiddenAppManager.captureDecoy(ctx, pkg, "1234"))
            assertEquals("ANODYNE-DATA", readMarker()) // captureDecoy doesn't wipe

            // 2. Populate the real content, capture it as the real snapshot — this DOES wipe.
            writeMarker("REAL-SECRET-DATA")
            assertTrue("captureReal should succeed", HiddenAppManager.captureReal(ctx, pkg, "8888"))
            assertNull("live data must be wiped after captureReal", readMarker())

            // 3. Decoy PIN entered at runtime -> anodyne data should reappear.
            HiddenAppManager.showDecoy(ctx, vault, "1234")
            assertEquals("showDecoy should restore the anodyne snapshot", "ANODYNE-DATA", readMarker())

            // 4. Real PIN entered at runtime -> real data should reappear.
            HiddenAppManager.revealReal(ctx, vault, "8888")
            assertEquals("revealReal should restore the real snapshot", "REAL-SECRET-DATA", readMarker())

            // 5. Wrong real PIN must not be able to open the hidden volume/reveal anything.
            writeMarker("SHOULD-NOT-CHANGE")
            HiddenAppManager.revealReal(ctx, vault, "0000")
            assertEquals("wrong PIN must not touch live data", "SHOULD-NOT-CHANGE", readMarker())
        } finally {
            HiddenVolume.unmount(HiddenVolume.Role.HIDDEN)
            HiddenVolume.unmount(HiddenVolume.Role.DECOY)
            Shell.cmd("rm -f /data/system/.sysstore").exec()
        }
    }

    /** Option A (full uninstall): captureUninstall snapshots the APK + data then genuinely
     *  uninstalls the app; revealReal reinstalls from the APK snapshot and restores the data.
     *  Uses a minimal disposable APK (com.thenile.disposabletest, hasCode=false, no real content)
     *  built and signed specifically for this test — every real installed third-party app on this
     *  device turned out to be unsuitable: Chrome/Maps/etc. are preloaded system apps where
     *  `pm uninstall` only reverts the OTA update rather than removing them; egg (an unupdated
     *  system app) fails uninstall outright (DELETE_FAILED_INTERNAL_ERROR); and the one genuine
     *  third-party app that WAS here (org.matrix.vector.manager) got consumed testing this same
     *  bug and has no network-fetchable replacement. */
    @Test
    fun uninstallAndReinstallCycle() {
        assumeTrue("needs root", Shell.getShell().isRoot)
        StorageMountManager.dmcryptBin = "${ctx.applicationInfo.nativeLibraryDir}/libdmcrypt.so"
        assumeTrue("dm-crypt helper must ship",
            Shell.cmd("test -x ${StorageMountManager.dmcryptBin}").exec().isSuccess)

        val targetPkg = "com.thenile.disposabletest"
        assumeTrue("target must be installed for this test", Shell.cmd("pm path $targetPkg").exec().isSuccess)
        val targetMarker = "/data/user/$userId/$targetPkg/files/adv_marker.txt"
        val vault = Vault(id = "test-uninstall", name = "Test", uninstallApps = listOf(targetPkg))

        try {
            val targetUid = Shell.cmd("$NS stat -c %u /data/user/$userId/$targetPkg").exec().out.firstOrNull()
            assertTrue("planting marker should succeed",
                Shell.cmd("$NS mkdir -p /data/user/$userId/$targetPkg/files",
                          "$NS sh -c \"echo -n TARGET-REAL-DATA > $targetMarker\"",
                          "$NS chown $targetUid:$targetUid /data/user/$userId/$targetPkg/files $targetMarker",
                          "$NS restorecon -R /data/user/$userId/$targetPkg/files").exec().isSuccess)

            assertTrue("captureUninstall should succeed", HiddenAppManager.captureUninstall(ctx, targetPkg, "8888"))
            assertFalse("target must actually be uninstalled",
                Shell.cmd("pm path $targetPkg").exec().isSuccess)

            HiddenAppManager.revealReal(ctx, vault, "8888")
            assertTrue("target must be reinstalled", Shell.cmd("pm path $targetPkg").exec().isSuccess)
            assertEquals("TARGET-REAL-DATA", Shell.cmd("$NS cat $targetMarker").exec().out.firstOrNull())
        } finally {
            HiddenVolume.unmount(HiddenVolume.Role.HIDDEN)
            Shell.cmd("rm -f /data/system/.sysstore").exec()
        }
    }
}
