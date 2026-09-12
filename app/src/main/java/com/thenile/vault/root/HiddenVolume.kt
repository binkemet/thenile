package com.thenile.vault.root

import android.util.Log
import com.topjohnwu.superuser.Shell

/** Fixed-size dm-crypt container holding TWO volumes for the app-snapshot store, so the store's very
 *  existence is deniable — not just its contents:
 *
 *    DECOY  volume: dm-crypt over the WHOLE container, key = deriveKey(decoyPin). Its ext4 spans the
 *                   entire container, so it "owns everything" — there is no size mismatch to explain.
 *    HIDDEN volume: dm-crypt over the back region [hiddenOffset, end), key = deriveKey(realPin). It
 *                   physically lives in what the decoy fs believes is free space. Without the real
 *                   PIN that tail is indistinguishable from random free space.
 *
 *  ==========================  VALIDATED ON EMULATOR (root_test35)  ==========================
 *  The full flow (losetup -o offset incl. >2GB, dm-crypt create over both volumes, mkfs/mount, a
 *  correct-key reopen, and a wrong-key mount rejection) has been run end-to-end via an on-device
 *  instrumented test (HiddenVolumeDeviceTest) using the shipped dmcrypt helper. Still only verified
 *  on x86_64 (the helper isn't yet wired into the Gradle build for arm64/arm/x86 — see AppDataVault
 *  packaging note) and on one emulator; a real device and the other ABIs remain to confirm.
 *
 *  Known ceilings (ponytail):
 *   - No hidden-volume write protection: if the DECOY fs ever fills into its tail it corrupts the
 *     hidden volume. Keep decoy usage well under hiddenOffset. VeraCrypt's "protect hidden" mode is
 *     the upgrade.
 *   - Single-snapshot deniability only: repeated seizures can reveal the hidden region by diffing
 *     the "free space" tail. Documented tradeoff, not a bug.
 *   - Fixed offset (open-source, so known): fine because dm-crypt makes the tail random regardless;
 *     PIN-derived offset is a marginal hardening left for later.
 *   - Container name below is still a fixed path — de-identify to a PIN-derived name in a later pass.
 */
object HiddenVolume {
    private const val TAG = "HiddenVolume"
    private const val CIPHER = "aes-xts-plain64"
    // ponytail: fixed anodyne-ish path; PIN-derived naming is the real de-ID, deferred.
    private const val CONTAINER = "/data/system/.sysstore"
    private const val NS = "nsenter -t 1 -m --"

    enum class Role { DECOY, HIDDEN }

    private fun dmName(role: Role) = if (role == Role.DECOY) "sysstore_d" else "sysstore_h"
    private fun mountPoint(role: Role) = "/data/system/.sysstore_mnt_${if (role == Role.DECOY) "d" else "h"}"

    /** Pure/testable: byte offset where the hidden volume starts. Decoy is 0 (whole container);
     *  hidden starts at 60% of the container, 512-aligned. */
    fun offsetBytes(role: Role, containerBytes: Long): Long {
        if (role == Role.DECOY) return 0L
        val off = containerBytes * 60 / 100
        return off - (off % 512)
    }

    private fun sh(cmd: String) = Shell.cmd(cmd).exec().isSuccess
    private fun shOut(cmd: String) = Shell.cmd(cmd).exec().out.firstOrNull()?.trim()
    private fun exists(path: String) = Shell.cmd("test -e '$path'").exec().isSuccess

    /** Ensure the container file exists (fixed size, decided once from free space). Returns its size
     *  in bytes, or null on failure. */
    private fun ensureContainer(): Long? {
        if (!exists(CONTAINER)) {
            val availKb = shOut("set -- \$(df -k -P /data | tail -n1); echo \$4")?.toLongOrNull()
            val marginKb = 512L * 1024
            val sizeBytes = (((availKb ?: (4L * 1024 * 1024)) - marginKb).coerceAtLeast(256L * 1024)) * 1024
            if (!sh("truncate -s $sizeBytes '$CONTAINER'")) return null
            sh("chcon u:object_r:shell_data_file:s0 '$CONTAINER'")
        }
        return shOut("stat -c %s '$CONTAINER'")?.toLongOrNull()
    }

    /** Mount [role]'s volume and return its mountpoint, or null. [formatIfNeeded] mkfs+formats when
     *  the region has no filesystem — pass true ONLY on a capture path (correct PIN by construction);
     *  never on a restore/open path, or a wrong PIN would reformat over real data. */
    fun mount(role: Role, pin: String, salt: String, formatIfNeeded: Boolean): String? {
        if (PrivilegeManager.currentTier() != PrivilegeTier.ROOT) { Log.e(TAG, "mount needs root"); return null }
        if (StorageMountManager.dmcryptBin.isEmpty()) { Log.e(TAG, "dmcrypt helper not set"); return null }
        val size = ensureContainer() ?: return null
        val mp = mountPoint(role)
        if (isMounted(mp)) return mp

        val offset = offsetBytes(role, size)
        val key = StorageMountManager.deriveKey(pin, salt)
        val loop = shOut("losetup -f") ?: return null
        if (!sh("losetup -o $offset '$loop' '$CONTAINER'")) return null

        val sectors = shOut("blockdev --getsz '$loop'")?.toLongOrNull()
        if (sectors == null) { sh("losetup -d '$loop'"); return null }

        Shell.cmd("${StorageMountManager.dmcryptBin} remove ${dmName(role)}").exec()
        val node = shOut("${StorageMountManager.dmcryptBin} create ${dmName(role)} $CIPHER $key '$loop' $sectors")
        if (node == null || !node.startsWith("/dev/")) { sh("losetup -d '$loop'"); return null }

        sh("mkdir -p '$mp'")
        if (!sh("$NS mount '$node' '$mp'")) {
            // No filesystem yet. Only safe to create one on a capture path.
            if (formatIfNeeded && sh("mkfs.ext4 -q -F -m 0 '$node'") && sh("$NS mount '$node' '$mp'")) {
                return mp
            }
            Log.e(TAG, "mount ${role.name}: mount failed (formatIfNeeded=$formatIfNeeded)")
            Shell.cmd("${StorageMountManager.dmcryptBin} remove ${dmName(role)}").exec()
            sh("losetup -d '$loop'")
            return null
        }
        return mp
    }

    /** Unmount and tear down [role]'s volume — leaves the container ciphertext intact. */
    fun unmount(role: Role) {
        val mp = mountPoint(role)
        sh("$NS umount -l '$mp'")
        Shell.cmd("${StorageMountManager.dmcryptBin} remove ${dmName(role)}").exec()
        // Detach whatever loop is backing the container for this role's offset (best-effort).
        shOut("losetup -j '$CONTAINER'")?.let { line ->
            line.substringBefore(':').trim().takeIf { it.startsWith("/dev/") }?.let { sh("losetup -d '$it'") }
        }
    }

    private fun isMounted(mp: String) = Shell.cmd("mount | grep -q ' $mp '").exec().isSuccess
}
