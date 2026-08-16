package com.thenile.vault.root

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

        /** Run a root command; log stderr and return true only on exit 0. */
        private fun sh(cmd: String): Boolean {
            val r = Shell.cmd(cmd).exec()
            if (!r.isSuccess) Log.e(TAG, "FAILED (code ${r.code}): $cmd  err=${r.err.joinToString("; ")}")
            return r.isSuccess
        }

        /** Run a root command, returning its first stdout line (trimmed) or null on failure/empty. */
        private fun shOut(cmd: String): String? {
            val r = Shell.cmd(cmd).exec()
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

        fun mountRealContainer(packages: List<String>, directories: List<String>, dummyDirectories: List<DummyDir>, pin: String, salt: String): Boolean {
            val key = deriveKey(pin, salt)
            // First, make sure dummy directories are unmounted so they don't cover the real container
            for (dummy in dummyDirectories) {
                sh("$NS umount -l ${dummy.target}")
            }
            
            if (dmcryptBin.isEmpty()) { Log.e(TAG, "dmcryptBin not set"); return false }
            Log.d(TAG, "Mounting real container")
            val staging = "/data/system/thenile_vault_mnt"
            sh("mkdir -p $staging")

            if (!isVaultMounted()) {
                val fresh = !exists(VAULT_IMG)
                if (fresh) {
                    // Sparse image sized to (free space on /data − safety margin) so the vault grows
                    // up to the device's real capacity. truncate keeps it sparse: only bytes the user
                    // actually writes consume disk. Parsed with `set --` (no awk — not on every ROM).
                    // ponytail: ceiling is physical /data free space at creation; recreate the vault to
                    // resize after the disk grows. Online grow (cryptsetup resize + resize2fs) if needed.
                    val availKb = shOut("set -- \$(df -k -P /data | tail -n1); echo \$4")
                        ?.trim()?.toLongOrNull()
                    val marginKb = 512L * 1024                        // leave 512 MiB for the OS
                    val sizeBytes = (((availKb ?: (4L * 1024 * 1024)) - marginKb)
                        .coerceAtLeast(256L * 1024)) * 1024           // floor 256 MiB
                    if (!sh("truncate -s $sizeBytes $VAULT_IMG")) return false
                    sh("chcon u:object_r:shell_data_file:s0 $VAULT_IMG")
                }
                val loop = shOut("losetup -f") ?: return false
                if (!sh("losetup $loop $VAULT_IMG")) return false
                val sectors = shOut("blockdev --getsz $loop")?.toLongOrNull()
                    ?: return false.also { sh("losetup -d $loop") }

                Shell.cmd("$dmcryptBin remove $DM_NAME").exec() // ignore missing/busy

                val node = shOut("$dmcryptBin create $DM_NAME $CIPHER $key $loop $sectors")
                if (node == null || !node.startsWith("/dev/")) {
                    Log.e(TAG, "dmcrypt create failed: $node")
                    sh("losetup -d $loop")
                    return false
                }
                // -m 0: no reserved blocks (personal vault, every block usable). lazy_*_init keeps a
                // multi-GB filesystem sparse — metadata is written on demand, not all up front.
                if (fresh && !sh("mkfs.ext4 -q -F -m 0 -E lazy_itable_init=1,lazy_journal_init=1 $node")) {
                    sh("$dmcryptBin remove $DM_NAME"); sh("losetup -d $loop"); return false
                }
                if (!sh("$NS mount $node $staging")) {
                    sh("$dmcryptBin remove $DM_NAME"); sh("losetup -d $loop"); return false
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
                // Use a safe hash/name for the internal folder
                val safeName = dir.replace("/", "_")
                val dirStaging = "$staging/dirs/$safeName"
                sh("mkdir -p $dirStaging")
                sh("mkdir -p $dir")
                if (!sh("$NS mount --bind $dirStaging $dir")) {
                    Log.e(TAG, "Failed to bind mount directory: $dir")
                }
            }
            
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

        fun hideProfile(profile: com.thenile.vault.state.Profile) {
            unmountAndLock(profile.packages, profile.directories, profile.dummyDirectories)
        }

        fun unhideProfile(profile: com.thenile.vault.state.Profile, pin: String, salt: String): Boolean {
            return mountRealContainer(profile.packages, profile.directories, profile.dummyDirectories, pin, salt)
        }

        fun mountDecoyDirectory(packages: List<String>, directories: List<String>, dummyDirectories: List<DummyDir>): Boolean {
            Log.d(TAG, "Mounting decoy directories")
            sh("mkdir -p /data/system/dummy_dir")
            val users = getUsers()
            var ok = true
            
            for (pkg in packages) {
                sh("pm hide $pkg")
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
            for (dummy in dummyDirectories) {
                sh("mkdir -p ${dummy.target}")
                sh("mkdir -p ${dummy.dummy}")
                if (!sh("$NS mount --bind ${dummy.dummy} ${dummy.target}")) ok = false
            }
            return ok
        }

        fun unmountAndLock(packages: List<String>, directories: List<String>, dummyDirectories: List<DummyDir>) {
            Log.d(TAG, "Unmounting and locking container")
            val staging = "/data/system/thenile_vault_mnt"
            val users = getUsers()

            for (pkg in packages) {
                sh("pm hide $pkg")
                for (userId in users) {
                    sh("am force-stop --user $userId $pkg")
                    sh("$NS umount -l /data/user/$userId/$pkg")
                }
            }
            for (dir in directories) {
                sh("$NS umount -l $dir")
                sh("rmdir $dir") // Delete empty folder so it completely disappears
            }
            
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


