package com.thenile.vault.root

import android.content.Context
import android.util.Log
import com.thenile.vault.state.DummyDir
import com.topjohnwu.superuser.Shell

class StorageMountManager {

    companion object {
        private const val TAG = "StorageMountManager"

        // Encrypted backing image + dm-crypt mapping for the real container.
        private const val VAULT_IMG = "/data/system/thenile_vault.img"
        private const val DM_NAME = "thenile_vault"
        // AES-256-XTS: the standard for block-device/FDE encryption (resists the manipulation &
        // watermarking weaknesses of CBC on disk). Needs a 64-byte key — deriveKey outputs 64.
        private const val CIPHER = "aes-xts-plain64"

        // Mounts must land in init's (PID 1) GLOBAL mount namespace, not the app's private one:
        // (1) the app's su session inherits the app's namespace where /data/data/<other> isn't
        // mountable, and (2) only a global mount is visible to the *target* app. Verified on
        // emulator: same mount fails in the app ns, succeeds via nsenter into init's ns.
        private const val NS = "nsenter -t 1 -m --"

        // Absolute path to our bundled dmcrypt helper (extracted to nativeLibraryDir). Set once
        // from an Activity because it depends on the install path. Root ioctls can't run in the
        // app's own process, so the helper does them as root via libsu.
        @JvmStatic var dmcryptBin: String = ""

        init {
            try {
                System.loadLibrary("rust_crypto")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load rust_crypto native library: ${e.message}")
            }
        }

        // @JvmStatic so the JNI symbol is ..._StorageMountManager_hashPin, not
        // ..._StorageMountManager_00024Companion_hashPin (which the Rust side doesn't export).
        @JvmStatic external fun hashPin(pin: String): String
        @JvmStatic external fun verifyPin(pin: String, stored: String): Boolean
        @JvmStatic external fun deriveKey(pin: String, salt: String): String
        @JvmStatic external fun encryptFileNative(pin: String, salt: String, srcPath: String, dstPath: String): Boolean
        @JvmStatic external fun decryptFileNative(pin: String, salt: String, srcPath: String, dstPath: String): Boolean

        /** Run a command at whatever privilege tier is available (root, else Shizuku's shell UID);
         *  log stderr and return true only on exit 0. Commands needing CAP_SYS_ADMIN (mount,
         *  nsenter, losetup, mkfs, the dmcrypt helper) fail harmlessly under Shizuku/none — callers
         *  that depend on those branch on PrivilegeManager.currentTier() instead of relying on this. */
        private fun sh(cmd: String): Boolean {
            val r = PrivilegedShell.exec(cmd)
            if (!r.isSuccess) Log.e(TAG, "FAILED (code ${r.code}): $cmd  err=${r.err.joinToString("; ")}")
            return r.isSuccess
        }

        /** Same tiering as sh(), returning the first stdout line (trimmed) or null on failure/empty. */
        private fun shOut(cmd: String): String? {
            val r = PrivilegedShell.exec(cmd)
            if (!r.isSuccess) {
                Log.e(TAG, "FAILED (code ${r.code}): $cmd  err=${r.err.joinToString("; ")}")
                return null
            }
            return r.out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun exists(path: String) = Shell.cmd("test -e $path").exec().isSuccess
        private fun loopFor(img: String): String? = shOut("losetup -j $img")?.substringBefore(':')?.trim()

        private fun getUsers(): List<String> {
            val out = shOut("pm list users") ?: return listOf("0")
            // Output format: UserInfo{0:Owner:13} or UserInfo{10:Work:30}
            return out.lines()
                .mapNotNull { line ->
                    val match = "UserInfo\\{([0-9]+):".toRegex().find(line)
                    match?.groupValues?.get(1)
                }
                .ifEmpty { listOf("0") }
        }

        /**
         * Open (creating + formatting on first use) the encrypted container and mount it to a staging area,
         * then bind-mount it over all target app data dirs (for all users) and custom directories.
         */
        fun isVaultMounted(): Boolean {
            return sh("mountpoint -q /data/system/thenile_vault_mnt")
        }

        fun mountRealContainer(
            packages: List<String>,
            directories: List<String>,
            dummyDirectories: List<DummyDir>,
            files: List<String> = emptyList(),
            pin: String,
            salt: String,
            context: Context? = null
        ): Boolean {
            val tier = PrivilegeManager.currentTier()
            // No CAP_SYS_ADMIN outside root: skip the mount/dm-crypt path entirely and restore
            // directories/files the way they were hidden — via SoftVault (see hide side below).
            if (tier != PrivilegeTier.ROOT) {
                for (pkg in packages) sh("pm unhide $pkg")
                if (context == null) {
                    Log.e(TAG, "mountRealContainer: no root/Shizuku and no context — cannot restore directories/files")
                    return false
                }
                return SoftVault.unhide(context, pin, salt, directories, files)
            }

            val key = deriveKey(pin, salt)
            // First, make sure dummy directories are unmounted so they don't cover the real container
            for (dummy in dummyDirectories) {
                sh("$NS umount -l ${dummy.target}")
            }

            val staging = "/data/system/thenile_vault_mnt"
            sh("mkdir -p $staging")

            if (dmcryptBin.isNotEmpty() && !isVaultMounted()) {
                val fresh = !exists(VAULT_IMG)
                if (fresh) {
                    val availKb = shOut("set -- \$(df -k -P /data | tail -n1); echo \$4")
                        ?.trim()?.toLongOrNull()
                    val marginKb = 512L * 1024
                    val sizeBytes = (((availKb ?: (4L * 1024 * 1024)) - marginKb)
                        .coerceAtLeast(256L * 1024)) * 1024
                    if (sh("truncate -s $sizeBytes $VAULT_IMG")) {
                        sh("chcon u:object_r:shell_data_file:s0 $VAULT_IMG")
                    }
                }
                val loop = shOut("losetup -f")
                if (loop != null && sh("losetup $loop $VAULT_IMG")) {
                    val sectors = shOut("blockdev --getsz $loop")?.toLongOrNull()
                    if (sectors != null) {
                        Shell.cmd("$dmcryptBin remove $DM_NAME").exec()
                        val node = shOut("$dmcryptBin create $DM_NAME $CIPHER $key $loop $sectors")
                        if (node != null && node.startsWith("/dev/")) {
                            if (fresh) {
                                sh("mkfs.ext4 -q -F -m 0 -E lazy_itable_init=1,lazy_journal_init=1 $node")
                            }
                            sh("$NS mount $node $staging")
                        } else {
                            sh("losetup -d $loop")
                        }
                    } else {
                        sh("losetup -d $loop")
                    }
                }
            }

            val users = getUsers()

            // Bind mount to each target app package across ALL users
            for (pkg in packages) {
                sh("pm unhide $pkg")
                val pkgStaging = "$staging/packages/$pkg"
                sh("mkdir -p $pkgStaging")
                
                for (userId in users) {
                    val userDataDir = "/data/user/$userId/$pkg"
                    if (exists(userDataDir)) {
                        if (!sh("$NS mount --bind $pkgStaging $userDataDir")) {
                            Log.e(TAG, "Failed to bind mount package: $pkg for user $userId")
                        }
                    }
                }
            }

            // Bind mount to each custom directory
            for (dir in directories) {
                val safeName = dir.replace("/", "_")
                val dirStaging = "$staging/dirs/$safeName"
                sh("mkdir -p $dirStaging")
                sh("mkdir -p $dir")
                if (!sh("$NS mount --bind $dirStaging $dir")) {
                    Log.e(TAG, "Failed to bind mount directory: $dir")
                }
            }

            // Decrypt & restore each individual custom file back to normal storage
            for (file in files) {
                val safeName = file.replace("/", "_") + ".enc"
                val encFile = "/data/system/thenile_vault_files/$safeName"
                val fileStaging = "$staging/files/$safeName"
                val sourceEnc = if (exists(encFile)) encFile else if (exists(fileStaging)) fileStaging else null

                if (sourceEnc != null) {
                    val parent = java.io.File(file).parent ?: "/sdcard"
                    val mediaParent = parent.replace("/sdcard", "/data/media/0").replace("/storage/emulated/0", "/data/media/0")
                    sh("mkdir -p '$parent'")
                    sh("mkdir -p '$mediaParent'")
                    val tempDec = "/data/local/tmp/dec_$safeName"
                    val ok = try {
                        decryptFileNative(pin, salt, sourceEnc, tempDec)
                    } catch (e: Throwable) {
                        Log.e(TAG, "decryptFileNative error: ${e.message}")
                        false
                    }
                    val mediaTarget = file.replace("/sdcard/", "/data/media/0/").replace("/storage/emulated/0/", "/data/media/0/")
                    if (ok && exists(tempDec)) {
                        sh("cp -p '$tempDec' '$mediaTarget'")
                        sh("cp -p '$tempDec' '$file' 2>/dev/null || true")
                        sh("rm -f '$tempDec'")
                    } else {
                        // Fallback copy if not encrypted
                        sh("cp -p '$sourceEnc' '$mediaTarget'")
                        sh("cp -p '$sourceEnc' '$file' 2>/dev/null || true")
                    }
                    sh("chown -R media_rw:media_rw '$mediaParent' 2>/dev/null || true")
                    sh("chmod 660 '$file' 2>/dev/null || true")
                }
            }
            sh("sync; echo 3 > /proc/sys/vm/drop_caches")
            
            // For encrypted dummies, bind mount their target to the vault when unlocked
            for (dummy in dummyDirectories) {
                if (dummy.encrypt) {
                    val safeName = dummy.target.replace("/", "_")
                    val dirStaging = "$staging/encrypted_dummies/$safeName"
                    sh("mkdir -p $dirStaging")
                    sh("mkdir -p ${dummy.target}")
                    if (!sh("$NS mount --bind $dirStaging ${dummy.target}")) {
                        Log.e(TAG, "Failed to bind mount encrypted dummy target: ${dummy.target}")
                    }
                }
            }

            return true
        }

        fun hideVault(vault: com.thenile.vault.state.Vault, pin: String = "", salt: String = "", context: Context? = null) {
            unmountAndLock(vault.packages, vault.directories, vault.dummyDirectories, vault.files, pin, salt, context)
        }

        fun unhideVault(vault: com.thenile.vault.state.Vault, pin: String, salt: String, context: Context? = null): Boolean {
            return mountRealContainer(vault.packages, vault.directories, vault.dummyDirectories, vault.files, pin, salt, context)
        }

        /** dummyDirectories (bind-mounted fake replacement content) stays root-only — without
         *  CAP_SYS_ADMIN there's no way to swap in a substitute directory, only to remove the real
         *  one, so under Shizuku/none `dummy` entries are silently skipped rather than half-applied. */
        fun mountDecoyDirectory(packages: List<String>, directories: List<String>, dummyDirectories: List<DummyDir>, files: List<String> = emptyList(), context: Context? = null): Boolean {
            Log.d(TAG, "Mounting decoy directories")
            val tier = PrivilegeManager.currentTier()
            for (pkg in packages) sh("pm hide $pkg")

            if (tier != PrivilegeTier.ROOT) {
                if (context == null) {
                    Log.e(TAG, "mountDecoyDirectory: no root/Shizuku and no context — cannot hide directories/files")
                    return false
                }
                // No pin/salt for the decoy path (matches the root path's use of dummy content,
                // not real encryption) — SoftVault still needs a key, so derive one from the decoy
                // trigger itself; it never needs to be remembered, only reproduced by unhide.
                return SoftVault.hide(context, "decoy", "decoy", directories, files)
            }

            sh("mkdir -p /data/system/dummy_dir")
            val users = getUsers()
            var ok = true

            for (pkg in packages) {
                for (userId in users) {
                    val userDataDir = "/data/user/$userId/$pkg"
                    if (exists(userDataDir)) {
                        if (!sh("$NS mount --bind /data/system/dummy_dir $userDataDir")) ok = false
                    }
                }
            }
            for (dir in directories) {
                sh("mkdir -p $dir")
                if (!sh("$NS mount --bind /data/system/dummy_dir $dir")) ok = false
            }
            for (file in files) {
                val mediaPath = file.replace("/sdcard/", "/data/media/0/").replace("/storage/emulated/0/", "/data/media/0/")
                sh("$NS umount -l '$file'")
                sh("rm -f '$mediaPath'")
                sh("rm -f '$file' 2>/dev/null || true")
            }
            sh("sync; echo 3 > /proc/sys/vm/drop_caches")
            for (dummy in dummyDirectories) {
                sh("mkdir -p ${dummy.target}")
                sh("mkdir -p ${dummy.dummy}")
                if (!sh("$NS mount --bind ${dummy.dummy} ${dummy.target}")) ok = false
            }
            return ok
        }

        fun unmountAndLock(
            packages: List<String>,
            directories: List<String>,
            dummyDirectories: List<DummyDir>,
            files: List<String> = emptyList(),
            pin: String = "",
            salt: String = "",
            context: Context? = null
        ) {
            Log.d(TAG, "Unmounting and locking container")
            val tier = PrivilegeManager.currentTier()
            val users = getUsers()

            for (pkg in packages) {
                sh("pm hide $pkg")
                for (userId in users) {
                    sh("am force-stop --user $userId $pkg")
                }
            }

            if (tier != PrivilegeTier.ROOT) {
                if (context != null) {
                    SoftVault.hide(context, pin, salt, directories, files)
                } else {
                    Log.e(TAG, "unmountAndLock: no root/Shizuku and no context — cannot hide directories/files")
                }
                return
            }

            for (pkg in packages) {
                for (userId in users) {
                    sh("$NS umount -l /data/user/$userId/$pkg")
                }
            }
            val staging = "/data/system/thenile_vault_mnt"
            for (dir in directories) {
                sh("$NS umount -l $dir")
                sh("rmdir $dir") // Delete empty folder so it completely disappears
            }

            // Encrypt and remove each individual custom file
            Log.d(TAG, "unmountAndLock processing ${files.size} files: $files")
            sh("mkdir -p /data/system/thenile_vault_files")
            for (file in files) {
                val safeName = file.replace("/", "_") + ".enc"
                val encFile = "/data/system/thenile_vault_files/$safeName"
                val mediaPath = file.replace("/sdcard/", "/data/media/0/").replace("/storage/emulated/0/", "/data/media/0/")
                val fileExists = exists(file) || exists(mediaPath)
                Log.d(TAG, "File $file (or $mediaPath) exists: $fileExists")
                if (fileExists) {
                    val realSrc = if (exists(file)) file else mediaPath
                    if (pin.isNotEmpty()) {
                        try {
                            val ok = encryptFileNative(pin, salt, realSrc, encFile)
                            Log.d(TAG, "encryptFileNative ok=$ok")
                        } catch (e: Throwable) {
                            Log.e(TAG, "encryptFileNative error: ${e.message}")
                            sh("cp -p '$realSrc' '$encFile'")
                        }
                    } else {
                        sh("cp -p '$realSrc' '$encFile'")
                    }
                }
                sh("$NS umount -l '$file'")
                sh("rm -f '$mediaPath'")
                sh("rm -f '$file' 2>/dev/null || true")
                Log.d(TAG, "After rm -f $file, exists: ${exists(file)}")
            }
            sh("sync; echo 3 > /proc/sys/vm/drop_caches")
            
            // Mount the dummy directories over the targets to hide them with fakes
            for (dummy in dummyDirectories) {
                sh("mkdir -p ${dummy.target}")
                sh("mkdir -p ${dummy.dummy}")
                sh("$NS mount --bind ${dummy.dummy} ${dummy.target}")
            }
            
            // Also unmount the staging area if it exists
            sh("$NS umount -l $staging")

            // Tear down the real container if it's up (harmless no-ops for the decoy/bind case).
            if (dmcryptBin.isNotEmpty()) sh("$dmcryptBin remove $DM_NAME")
            loopFor(VAULT_IMG)?.let { sh("losetup -d $it") }
        }
    }
}


