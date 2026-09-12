package com.thenile.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thenile.vault.root.AppDataVault
import com.thenile.vault.root.HiddenVolume
import com.thenile.vault.root.StorageMountManager
import com.thenile.vault.state.VaultStateManager
import com.topjohnwu.superuser.Shell
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regression test for a real bug: every app process (including a root shell forked from one) gets
 *  its own private mount namespace for storage sandboxing, which does NOT see other apps' CE data
 *  under /data/user/<id>/<pkg> — confirmed by cat/ls/stat/tar all failing identically (silently, no
 *  stderr) against another app's directory, while the same shell reads/writes Nile's own data fine,
 *  and the identical commands succeed via `adb shell su -c` (adbd isn't sandboxed like an app
 *  process). Not permissions/capabilities/SELinux-category — CapEff matched adb's su exactly, and
 *  relabeling the target to Nile's own SELinux category didn't help either. Fix (in AppDataVault):
 *  run those commands via `nsenter -t 1 -m --` (PID 1's mount namespace), the same trick
 *  HiddenVolume already uses for its own mount() call. This test reproduces the exact real
 *  captureReal() sequence — mount, then a tar touching another app's data — since the bug only
 *  showed up in that order, not when AppDataVaultTest exercises tar in isolation. */
@RunWith(AndroidJUnit4::class)
class AppDataVaultAfterMountTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tarOnAnotherAppsDataSucceedsAfterHiddenVolumeMount() {
        assumeTrue("needs root", Shell.getShell().isRoot)
        StorageMountManager.dmcryptBin = "${ctx.applicationInfo.nativeLibraryDir}/libdmcrypt.so"
        assumeTrue("dm-crypt helper must ship",
            Shell.cmd("test -x ${StorageMountManager.dmcryptBin}").exec().isSuccess)
        val salt = VaultStateManager.getInstance(ctx).keySalt()

        try {
            val mp = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, "8888", salt, formatIfNeeded = true)
            assertTrue("mount should succeed", mp != null)

            val tarRes = Shell.cmd(AppDataVault.tarCreateCmd("com.android.egg", 0, "${ctx.cacheDir}/.repro.tar")).exec()
            assertTrue("tar on another app's data must succeed after a HiddenVolume mount " +
                "(code=${tarRes.code} out=${tarRes.out} err=${tarRes.err})", tarRes.isSuccess)
        } finally {
            HiddenVolume.unmount(HiddenVolume.Role.HIDDEN)
            Shell.cmd("rm -f /data/system/.sysstore '${ctx.cacheDir}/.repro.tar'").exec()
        }
    }
}
