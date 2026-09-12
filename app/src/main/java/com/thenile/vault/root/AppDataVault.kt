package com.thenile.vault.root

import android.util.Log

/** Snapshot / restore of an app's private data (the "option B" primitive): back up the real data
 *  into the hidden volume and wipe it from the live system, and restore either the real data (real
 *  PIN) or an anodyne snapshot (decoy PIN) in its place. Copy-based, not bind-mount — the data
 *  genuinely moves, so nothing real is left on disk in the decoy state.
 *
 *  Root only: tar of another app's /data/user dir + chown + restorecon need capabilities the
 *  Shizuku shell-UID doesn't have. Snapshots are encrypted with the same native AES path Nile uses
 *  for files (StorageMountManager.encryptFileNative), so a snapshot at rest is just ciphertext.
 *
 *  ponytail: user 0 + CE data only. Per-user (Android work profile) and DE data (/data/user_de) are the
 *  upgrade path if a target app stores secrets there — add more dirs to snapshot/restore then. */
object AppDataVault {
    private const val TAG = "AppDataVault"
    // Every app process (including one our root shell was forked from) gets its own private mount
    // namespace for storage sandboxing, and that namespace does NOT see other apps' CE data —
    // confirmed on-device: cat/ls/stat/tar all fail identically (silently, no stderr) against
    // another app's /data/user/<id>/<pkg>, from the SAME root shell that reads/writes our own data
    // fine, while the identical commands succeed via `adb shell su -c` (adbd isn't sandboxed like
    // an app process). Not a permissions/capability/SELinux-category issue — verified CapEff is
    // identical to adb's su, and relabeling the target to our own SELinux category didn't help
    // either. Fix: run in PID 1's (init's) mount namespace instead, same trick HiddenVolume already
    // uses for its own mount() call.
    private const val NS = "nsenter -t 1 -m --"

    /** Regenerated or symlinked subdirs — excluded so a snapshot stays small and restore is clean. */
    private val EXCLUDES = listOf("cache", "code_cache", "no_backup", "lib")

    private fun ceDir(userId: Int) = "/data/user/$userId"

    /** Pure/testable: the tar command that packs <pkg>'s CE data dir into [outTar], relative to the
     *  user dir so it restores back to the same place, minus the throwaway subdirs. Runs in PID 1's
     *  mount namespace (see NS) — this reads another app's data, which our own sandboxed namespace
     *  can't see. */
    fun tarCreateCmd(pkg: String, userId: Int, outTar: String): String {
        val excludes = EXCLUDES.joinToString(" ") { "--exclude=$pkg/$it" }
        return "$NS tar $excludes -cf '$outTar' -C '${ceDir(userId)}' '$pkg'"
    }

    /** Pure/testable: extract a snapshot back into the user dir (paths inside are <pkg>/...). Same
     *  cross-namespace need as tarCreateCmd, just writing instead of reading. */
    fun tarExtractCmd(inTar: String, userId: Int): String =
        "$NS tar -xf '$inTar' -C '${ceDir(userId)}'"

    /** Pure/testable: turn `pm path <pkg>` output ("package:/data/app/.../base.apk" lines, one per
     *  split) into the APK file paths to back up. */
    fun parseApkPaths(pmPathOutput: List<String>): List<String> =
        pmPathOutput.map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.endsWith(".apk") }

    /** encryptFileNative/decryptFileNative run as THIS app's own uid (JNI, in-process) — they can
     *  only create files where that uid has write permission. encDest/encSrc are caller-chosen and
     *  routinely point somewhere only root can write (e.g. a HiddenVolume mount point, freshly
     *  mkfs'd root:root 755 — the app can read there but not create a new file). So every native
     *  encrypt/decrypt call is staged through [scratchDir] (an app-writable dir, e.g. cacheDir),
     *  with PrivilegedShell (root, bypasses all of that) doing the actual placement/fetch on either
     *  side. Confirmed on-device: encryptFileNative straight to a HiddenVolume mount point silently
     *  fails for exactly this reason. */
    private fun myUid() = android.os.Process.myUid()

    /** Back up [pkg]'s data into [encDest] (encrypted with pin/salt). If [wipeAfter], the live data
     *  is cleared so nothing real remains in the visible/decoy state. Returns true on success. */
    fun snapshot(pkg: String, userId: Int, pin: String, salt: String, encDest: String, wipeAfter: Boolean, scratchDir: String): Boolean {
        if (PrivilegeManager.currentTier() != PrivilegeTier.ROOT) {
            Log.e(TAG, "snapshot needs root")
            return false
        }
        val tmp = "$scratchDir/.adv_${pkg.hashCode()}.tar"
        val encScratch = "$scratchDir/.adv_${pkg.hashCode()}.enc"
        PrivilegedShell.exec("am force-stop '$pkg'")
        val tarRes = PrivilegedShell.exec(tarCreateCmd(pkg, userId, tmp))
        if (!tarRes.isSuccess) {
            PrivilegedShell.exec("rm -f '$tmp'")
            Log.e(TAG, "snapshot: tar failed for $pkg code=${tarRes.code} out=${tarRes.out} err=${tarRes.err}")
            return false
        }
        val enc = try {
            StorageMountManager.encryptFileNative(pin, salt, tmp, encScratch)
        } catch (e: Throwable) {
            Log.e(TAG, "snapshot: encrypt failed: ${e.message}"); false
        }
        PrivilegedShell.exec("rm -f '$tmp'")
        if (!enc) { PrivilegedShell.exec("rm -f '$encScratch'"); return false }
        // Root places the encrypted blob at its real (possibly root-only-writable) destination.
        val placed = PrivilegedShell.exec("cp -p '$encScratch' '$encDest'").isSuccess
        PrivilegedShell.exec("rm -f '$encScratch'")
        if (!placed) { Log.e(TAG, "snapshot: placing encrypted blob failed for $pkg"); return false }
        if (wipeAfter) {
            PrivilegedShell.exec("pm clear '$pkg'")
            // pm clear's "Success" is not synchronous with the actual unlink — confirmed on-device
            // (Magisk root): the plaintext can still be sitting there long after pm clear returns.
            // The whole point of wipeAfter is "nothing real left on disk", so force it directly
            // rather than trust that signal. Needs NS: another app's data, invisible in our own
            // sandboxed mount namespace.
            PrivilegedShell.exec("$NS rm -rf '${ceDir(userId)}/$pkg'")
        }
        return true
    }

    /** Restore a snapshot [encSrc] (decrypted with pin/salt) into [pkg]'s live data. Used for both
     *  the real data (real PIN) and the anodyne decoy data (decoy PIN) — same mechanism, different
     *  snapshot. pm clear first gives a fresh dir with the app's current uid + correct SELinux
     *  context; we extract over it, then re-fix ownership and relabel. */
    fun restore(pkg: String, userId: Int, pin: String, salt: String, encSrc: String, scratchDir: String): Boolean {
        if (PrivilegeManager.currentTier() != PrivilegeTier.ROOT) {
            Log.e(TAG, "restore needs root")
            return false
        }
        val encScratch = "$scratchDir/.adv_${pkg.hashCode()}.enc"
        val tmp = "$scratchDir/.adv_${pkg.hashCode()}.tar"
        // Root fetches the (possibly root-only) source into the app's own scratch dir and hands
        // ownership over, so decryptFileNative (app-uid) can actually read it.
        val staged = PrivilegedShell.exec(
            "cp -p '$encSrc' '$encScratch'",
            "chown ${myUid()}:${myUid()} '$encScratch'"
        ).isSuccess
        if (!staged) { Log.e(TAG, "restore: staging encrypted blob failed for $pkg"); return false }
        val dec = try {
            StorageMountManager.decryptFileNative(pin, salt, encScratch, tmp)
        } catch (e: Throwable) {
            Log.e(TAG, "restore: decrypt failed: ${e.message}"); false
        }
        PrivilegedShell.exec("rm -f '$encScratch'")
        if (!dec) { PrivilegedShell.exec("rm -f '$tmp'"); return false }

        PrivilegedShell.exec("am force-stop '$pkg'")
        PrivilegedShell.exec("pm clear '$pkg'")
        val dir = "${ceDir(userId)}/$pkg"
        // pm clear's "Success" isn't synchronous with the actual unlink (same issue as snapshot()'s
        // wipeAfter) — without forcing it, the delayed real wipe can land AFTER our tar extract
        // below and destroy the just-restored files. Force it complete before extracting into $dir.
        PrivilegedShell.exec("$NS rm -rf '$dir'")
        val extracted = PrivilegedShell.exec(tarExtractCmd(tmp, userId)).isSuccess
        // uid the freshly-cleared dir belongs to → what the restored tree must be chown'd to. Each
        // command needs its own NS wrapper — nsenter only affects the process it directly execs
        // into, and every one of these touches another app's data (invisible in our sandboxed
        // namespace). Computed as a separate step rather than a shell variable inside the nsentered
        // process, to avoid three-deep quoting (outer shell -> nsenter -> inner sh -c) for one line.
        val uid = if (extracted) PrivilegedShell.exec("$NS stat -c %u '$dir'").out.firstOrNull()?.trim() else null
        val ok = uid != null && PrivilegedShell.exec(
            "$NS chown -R $uid:$uid '$dir'",
            "$NS restorecon -R '$dir'"
        ).isSuccess
        PrivilegedShell.exec("rm -f '$tmp'")
        if (!ok) Log.e(TAG, "restore: extract/chown/relabel failed for $pkg")
        return ok
    }

    /** Back up [pkg]'s APK(s) — base + any splits — into [encDest] (encrypted). Pairs with a data
     *  snapshot for the "uninstall" mode: with both, the app can be fully removed and rebuilt. */
    fun snapshotApks(pkg: String, pin: String, salt: String, encDest: String, scratchDir: String): Boolean {
        if (PrivilegeManager.currentTier() != PrivilegeTier.ROOT) { Log.e(TAG, "snapshotApks needs root"); return false }
        val paths = parseApkPaths(PrivilegedShell.exec("pm path '$pkg'").out)
        if (paths.isEmpty()) { Log.e(TAG, "snapshotApks: no APK path for $pkg"); return false }
        val dir = "$scratchDir/.adva_${pkg.hashCode()}"
        val tmp = "$dir.tar"
        val encScratch = "$dir.enc"
        PrivilegedShell.exec("rm -rf '$dir'", "mkdir -p '$dir'")
        for (p in paths) PrivilegedShell.exec("cp -p '$p' '$dir/'")
        val tarred = PrivilegedShell.exec("tar -cf '$tmp' -C '$dir' .").isSuccess
        val enc = tarred && try { StorageMountManager.encryptFileNative(pin, salt, tmp, encScratch) }
            catch (e: Throwable) { Log.e(TAG, "snapshotApks: encrypt failed: ${e.message}"); false }
        val placed = enc && PrivilegedShell.exec("cp -p '$encScratch' '$encDest'").isSuccess
        if (enc && !placed) Log.e(TAG, "snapshotApks: placing encrypted blob failed for $pkg")
        PrivilegedShell.exec("rm -rf '$dir' '$tmp' '$encScratch'")
        return placed
    }

    /** Reinstall APK(s) from [encSrc]. Splits go in together via `pm install-multiple` so the app is
     *  reconstructed exactly. Restore its data snapshot separately afterwards. */
    fun restoreApks(pin: String, salt: String, encSrc: String, scratchDir: String): Boolean {
        if (PrivilegeManager.currentTier() != PrivilegeTier.ROOT) { Log.e(TAG, "restoreApks needs root"); return false }
        val dir = "$scratchDir/.adva_r_${encSrc.hashCode()}"
        val tmp = "$dir.tar"
        val encScratch = "$dir.enc"
        val staged = PrivilegedShell.exec("cp -p '$encSrc' '$encScratch'", "chown ${myUid()}:${myUid()} '$encScratch'").isSuccess
        val dec = staged && try { StorageMountManager.decryptFileNative(pin, salt, encScratch, tmp) }
            catch (e: Throwable) { Log.e(TAG, "restoreApks: decrypt failed: ${e.message}"); false }
        PrivilegedShell.exec("rm -f '$encScratch'")
        if (!dec) { PrivilegedShell.exec("rm -rf '$dir' '$tmp'"); return false }
        PrivilegedShell.exec("rm -rf '$dir'", "mkdir -p '$dir'", "tar -xf '$tmp' -C '$dir'")
        // Shell glob expands the split set; install-multiple accepts base + splits atomically.
        val ok = PrivilegedShell.exec("cd '$dir' && pm install-multiple *.apk").isSuccess
        PrivilegedShell.exec("rm -rf '$dir' '$tmp'")
        if (!ok) Log.e(TAG, "restoreApks: install-multiple failed")
        return ok
    }

    /** Fully remove the app (APK + data + packages.xml entry) — unlike `pm hide`, this leaves no
     *  installed-but-hidden record. Only safe once its APK + data are snapshotted. */
    fun uninstall(pkg: String): Boolean =
        PrivilegeManager.currentTier() == PrivilegeTier.ROOT &&
            PrivilegedShell.exec("pm uninstall '$pkg'").isSuccess
}
