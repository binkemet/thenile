package com.thenile.vault.root

import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import java.io.InputStream
import java.io.OutputStream

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
            // Fill the WHOLE container with random before any filesystem is written, so the hidden
            // volume's tail is indistinguishable from the decoy's free space — the core deniability
            // requirement (a hidden volume in a field of zeros is an obvious tell). Fail closed: a
            // zero-filled container is not deniable, so don't silently ship one.
            if (!randomFill()) { sh("rm -f '$CONTAINER'"); Log.e(TAG, "random-fill failed"); return null }
        }
        return shOut("stat -c %s '$CONTAINER'")?.toLongOrNull()
    }

    /** Overwrite the entire container with random data. Done by writing zeros THROUGH a throwaway
     *  dm-crypt mapping keyed with a random 64-byte key: AES(zeros) is pseudo-random ciphertext, so
     *  the raw file ends up uniformly random at AES-NI speed instead of the /dev/urandom bottleneck.
     *  ponytail: still a full-device write (GB), a one-time cost on first container creation — the
     *  price of deniability; a progress UI is the upgrade if it ever feels slow. */
    private fun randomFill(): Boolean {
        val loop = shOut("losetup -f") ?: return false
        if (!sh("losetup '$loop' '$CONTAINER'")) return false
        val sectors = shOut("blockdev --getsz '$loop'")?.toLongOrNull()
        if (sectors == null) { sh("losetup -d '$loop'"); return false }
        // 64 random bytes -> 128 hex chars, matching deriveKey's length for aes-xts-plain64.
        val key = shOut("head -c 64 /dev/urandom | od -An -v -tx1 | tr -d ' \\n'")
        if (key == null || key.length < 128) { sh("losetup -d '$loop'"); return false }
        val name = "sysstore_fill"
        Shell.cmd("${StorageMountManager.dmcryptBin} remove $name").exec()
        val node = shOut("${StorageMountManager.dmcryptBin} create $name $CIPHER $key '$loop' $sectors")
        if (node == null || !node.startsWith("/dev/")) { sh("losetup -d '$loop'"); return false }
        // dd runs until the mapping's end (short write / ENOSPC) and exits non-zero — expected, the
        // fill is complete regardless, so don't gate on its exit code.
        Shell.cmd("dd if=/dev/zero of='$node' bs=1048576").exec()
        Shell.cmd("${StorageMountManager.dmcryptBin} remove $name").exec()
        sh("losetup -d '$loop'")
        // Verify the RAW container (bypassing dm-crypt) actually holds non-zero ciphertext now — read
        // one sector at the hidden-volume offset, the deniability-critical tail. Reading the mapper
        // would just give back the zeros we wrote in plaintext, so read the file directly.
        val tail = offsetBytes(Role.HIDDEN, sectors * 512)
        val sample = shOut("dd if='$CONTAINER' bs=512 count=1 skip=${tail / 512} 2>/dev/null | od -An -tx1 | tr -d ' \\n'")
        return sample != null && sample.isNotEmpty() && sample.any { it != '0' }
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

    /** Tear down BOTH volumes and delete the container ciphertext entirely — the "wipe on
     *  uninstall" / panic path. Best-effort: every step is independent so a failure of one still
     *  runs the rest. After this the hidden data is unrecoverable (no salt persistence helps). */
    fun destroy(): Boolean {
        unmount(Role.DECOY)
        unmount(Role.HIDDEN)
        // Any loop still backing the container (either offset) must go before the file can be freed.
        shOut("losetup -j '$CONTAINER'")?.let { out ->
            out.lineSequence().forEach { line ->
                line.substringBefore(':').trim().takeIf { it.startsWith("/dev/") }?.let { sh("losetup -d '$it'") }
            }
        }
        sh("rm -rf '${mountPoint(Role.DECOY)}' '${mountPoint(Role.HIDDEN)}'")
        return sh("rm -f '$CONTAINER'")
    }

    fun containerExists() = exists(CONTAINER)

    /** Container size in bytes, or null if it doesn't exist. */
    fun containerSize(): Long? = shOut("stat -c %s '$CONTAINER'")?.toLongOrNull()

    /** Copy the whole container ciphertext OUT to [out] (a user-picked OTG/SAF file) so it can live
     *  off-device. Read as root via libsu since the file is root-owned in /data/system. Caller runs
     *  this off the main thread — the container is often GB-sized. Returns false if there's nothing
     *  to export or the copy fails. NOTE: the container only decrypts with the matching key salt +
     *  real PIN, so a full off-device restore also needs the /data/system config (or a .nile backup)
     *  that carries the salt — see SettingsManager.restoreFromSystemConfig. */
    fun exportContainer(out: OutputStream): Boolean {
        if (PrivilegeManager.currentTier() != PrivilegeTier.ROOT) return false
        if (!containerExists()) return false
        return try {
            SuFileInputStream.open(CONTAINER).use { it.copyTo(out, 1 shl 20) }
            true
        } catch (e: Throwable) { Log.e(TAG, "exportContainer failed", e); false }
    }

    /** Restore a container ciphertext IN from [input] (a user-picked OTG/SAF file), overwriting any
     *  existing container. Tears down active mounts first, then restores perms/label so mount() works.
     *  Caller runs off the main thread. Returns false on failure. */
    fun importContainer(input: InputStream): Boolean {
        if (PrivilegeManager.currentTier() != PrivilegeTier.ROOT) return false
        unmount(Role.DECOY)
        unmount(Role.HIDDEN)
        return try {
            SuFileOutputStream.open(CONTAINER).use { input.copyTo(it, 1 shl 20) }
            sh("chmod 600 '$CONTAINER'")
            sh("chcon u:object_r:shell_data_file:s0 '$CONTAINER'")
            true
        } catch (e: Throwable) { Log.e(TAG, "importContainer failed", e); false }
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
