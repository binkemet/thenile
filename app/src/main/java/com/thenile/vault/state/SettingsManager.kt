package com.thenile.vault.state

import android.content.Context
import android.content.SharedPreferences
import com.topjohnwu.superuser.Shell
import org.json.JSONArray
import org.json.JSONObject

data class DummyDir(val target: String, val dummy: String, val encrypt: Boolean = false)

data class Profile(
    val id: String,
    var name: String,
    var packages: List<String>,
    var directories: List<String>,
    var dummyDirectories: List<DummyDir>,
    var isActive: Boolean,
    var hideOnDecoy: Boolean = true,
    var decoyPin: String = ""
)

open class SettingsManager(private val context: Context) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("vault_settings", Context.MODE_PRIVATE)
    }

    private var _cachedProfiles: List<Profile>? = null

    open var profiles: List<Profile>
        get() {
            _cachedProfiles?.let { return it }
            val jsonStr = prefs.getString("profiles", null)
            if (jsonStr == null) {
                // Migration from old flat list
                val oldPkgs = prefs.getString("targetPackages", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                val oldDirs = prefs.getString("targetDirectories", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()


                val defaultList = listOf(
                    Profile(
                        id = "default",
                        name = "Default Profile",
                        packages = oldPkgs,
                        directories = oldDirs,
                        dummyDirectories = emptyList(),
                        isActive = true,
                        hideOnDecoy = true,
                        decoyPin = "1234"
                    )
                )
                _cachedProfiles = defaultList
                return defaultList
            }
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Profile>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val pkgs = mutableListOf<String>()
                val pkgsArr = obj.optJSONArray("packages")
                if (pkgsArr != null) {
                    for (j in 0 until pkgsArr.length()) pkgs.add(pkgsArr.getString(j))
                }
                val dirs = mutableListOf<String>()
                val dirsArr = obj.optJSONArray("directories")
                if (dirsArr != null) {
                    for (j in 0 until dirsArr.length()) dirs.add(dirsArr.getString(j))
                }
                val dummyDirs = mutableListOf<DummyDir>()
                val dummyArr = obj.optJSONArray("dummyDirectories")
                if (dummyArr != null) {
                    for (j in 0 until dummyArr.length()) {
                        val dObj = dummyArr.getJSONObject(j)
                        dummyDirs.add(DummyDir(dObj.getString("target"), dObj.getString("dummy"), dObj.optBoolean("encrypt", false)))
                    }
                }
                list.add(Profile(
                    id = obj.optString("id"),
                    name = obj.optString("name", "Unnamed Profile"),
                    packages = pkgs,
                    directories = dirs,
                    dummyDirectories = dummyDirs,
                    isActive = obj.optBoolean("isActive", false),
                    hideOnDecoy = obj.optBoolean("hideOnDecoy", false),
                    decoyPin = obj.optString("decoyPin", if (obj.optBoolean("hideOnDecoy", false)) "1234" else "")
                ))
            }
            _cachedProfiles = list
            return list
        }
        set(value) {
            _cachedProfiles = value
            val array = JSONArray()
            value.forEach { p ->
                val obj = JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                val pkgs = JSONArray()
                p.packages.forEach { pkgs.put(it) }
                obj.put("packages", pkgs)
                val dirs = JSONArray()
                p.directories.forEach { dirs.put(it) }
                obj.put("directories", dirs)
                val dummyArr = JSONArray()
                p.dummyDirectories.forEach { d ->
                    val dObj = JSONObject()
                    dObj.put("target", d.target)
                    dObj.put("dummy", d.dummy)
                    dObj.put("encrypt", d.encrypt)
                    dummyArr.put(dObj)
                }
                obj.put("dummyDirectories", dummyArr)
                obj.put("isActive", p.isActive)
                obj.put("hideOnDecoy", p.hideOnDecoy)
                obj.put("decoyPin", p.decoyPin)
                array.put(obj)
            }
            prefs.edit().putString("profiles", array.toString()).commit()
            syncToSystem()
        }

    val targetPackages: List<String>
        get() = profiles.filter { it.isActive }.flatMap { it.packages }.distinct()

    val targetDirectories: List<String>
        get() = profiles.filter { it.isActive }.flatMap { it.directories }.distinct()
        
    val targetDummyDirectories: List<DummyDir>
        get() = profiles.filter { it.isActive }.flatMap { it.dummyDirectories }.distinct()

    val decoyPackages: List<String>
        get() = profiles.filter { it.hideOnDecoy }.flatMap { it.packages }.distinct()
        
    val decoyDirectories: List<String>
        get() = profiles.filter { it.hideOnDecoy }.flatMap { it.directories }.distinct()
        
    val decoyDummyDirectories: List<DummyDir>
        get() = profiles.filter { it.hideOnDecoy }.flatMap { it.dummyDirectories }.distinct()

    fun getDecoyPackagesForCode(code: String): List<String> {
        return profiles.filter { it.decoyPin == code || (code == codeDecoy && it.decoyPin.isNotBlank()) }
            .flatMap { it.packages }.distinct()
    }

    fun getDecoyDirectoriesForCode(code: String): List<String> {
        return profiles.filter { it.decoyPin == code || (code == codeDecoy && it.decoyPin.isNotBlank()) }
            .flatMap { it.directories }.distinct()
    }

    fun getDecoyDummyDirectoriesForCode(code: String): List<DummyDir> {
        return profiles.filter { it.decoyPin == code || (code == codeDecoy && it.decoyPin.isNotBlank()) }
            .flatMap { it.dummyDirectories }.distinct()
    }

    var codeLock: String
        get() = prefs.getString("codeLock", "1111") ?: "1111"
        set(value) { prefs.edit().putString("codeLock", value).commit() }

    var codeDecoy: String
        get() = prefs.getString("codeDecoy", "1234") ?: "1234"
        set(value) { prefs.edit().putString("codeDecoy", value).commit(); syncToSystem() }

    var codeUnlock: String
        get() = prefs.getString("codeUnlock", "9876") ?: "9876"
        set(value) { prefs.edit().putString("codeUnlock", value).commit() }

        
    var adminLockMethod: String
        get() = prefs.getString("adminLockMethod", "biometric") ?: "biometric"
        set(value) { prefs.edit().putString("adminLockMethod", value).commit() }

    var adminCustomPin: String
        get() = prefs.getString("adminCustomPin", "") ?: ""
        set(value) { prefs.edit().putString("adminCustomPin", value).commit() }

    var codeAdmin: String
        get() = prefs.getString("codeAdmin", "3333") ?: "3333"
        set(value) { prefs.edit().putString("codeAdmin", value).commit() }

    var hideAppIcon: Boolean
        get() = prefs.getBoolean("hideAppIcon", false)
        set(value) {
            prefs.edit().putBoolean("hideAppIcon", value).commit()
            Thread {
                val compName = android.content.ComponentName(context, "com.thenile.vault.ui.AdminActivity")
                val newState = if (value) {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                }
                try {
                    context.packageManager.setComponentEnabledSetting(compName, newState, android.content.pm.PackageManager.DONT_KILL_APP)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (value) {
                    Shell.cmd("pm hide com.thenile.vault").exec()
                } else {
                    Shell.cmd("pm unhide com.thenile.vault").exec()
                    Shell.cmd("pm enable com.thenile.vault/.ui.AdminActivity").exec()
                }
            }.start()
        }

    var enableTile: Boolean
        get() = prefs.getBoolean("enableTile", true)
        set(value) {
            prefs.edit().putBoolean("enableTile", value).commit()
            setComponentEnabled(".services.NileTileService", value)
        }

    var enableDeepLink: Boolean
        get() = prefs.getBoolean("enableDeepLink", true)
        set(value) {
            prefs.edit().putBoolean("enableDeepLink", value).commit()
            setComponentEnabled(".ui.AdminActivityDeepLink", value)
        }

    private fun setComponentEnabled(relativeName: String, enabled: Boolean) {
        Thread {
            val compName = android.content.ComponentName(context, "com.thenile.vault$relativeName")
            val newState = if (enabled) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                context.packageManager.setComponentEnabledSetting(compName, newState, android.content.pm.PackageManager.DONT_KILL_APP)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    var enableVolumeKeys: Boolean
        get() = prefs.getBoolean("enableVolumeKeys", false)
        set(value) { prefs.edit().putBoolean("enableVolumeKeys", value).commit() }

    /** Hooks the REAL system Calculator app (not a fake one built into Nile) — typing
     *  calculatorTriggerExpression into it opens Admin. Needs syncToSystem() since the hook
     *  runs inside the calculator app's own process, a separate app from Nile. */
    var enableCalculatorDecoy: Boolean
        get() = prefs.getBoolean("enableCalculatorDecoy", false)
        set(value) { prefs.edit().putBoolean("enableCalculatorDecoy", value).commit(); syncToSystem() }

    /** The math expression that, when fully typed into the real Calculator app, opens Admin.
     *  Matched after stripping whitespace and normalizing Unicode math glyphs (−×÷) to
     *  ASCII, so it doesn't matter which symbols the calculator's own keypad produces. */
    var calculatorTriggerExpression: String
        get() = prefs.getString("calculatorTriggerExpression", "47-87+23") ?: "47-87+23"
        set(value) { prefs.edit().putString("calculatorTriggerExpression", value).commit(); syncToSystem() }

    var enableFakeCrash: Boolean
        get() = prefs.getBoolean("enableFakeCrash", false)
        set(value) { prefs.edit().putBoolean("enableFakeCrash", value).commit() }

    /** "off" | "fake_wrong_pin" | "one_time_unlock". Default off: this hooks the real Android
     *  keyguard, so it stays inert until explicitly enabled. */
    var decoyLockScreenMode: String
        get() = prefs.getString("decoyLockScreenMode", "off") ?: "off"
        set(value) { prefs.edit().putString("decoyLockScreenMode", value).commit() ; syncToSystem() }

    /** All codes that should trigger the decoy: the global code plus every profile's own. */
    val decoyCodes: List<String>
        get() = (listOf(codeDecoy) + profiles.mapNotNull { it.decoyPin.ifBlank { null } }).distinct()

    /** How many times one_time_unlock may fire before it stops unlocking (and reverts to a plain
     *  wrong-PIN rejection) until re-armed. 0 = unlimited. Default 1 matches the mode's name. */
    var decoyUnlockLimit: Int
        get() = prefs.getInt("decoyUnlockLimit", 1)
        set(value) { prefs.edit().putInt("decoyUnlockLimit", value.coerceAtLeast(0)).commit(); syncToSystem() }

    /** How many times one_time_unlock has actually fired since the last re-arm. The hook
     *  increments this itself (as root, via triggerDecoyHide) each time it grants an unlock.
     *  Reading it back here lets Admin show "N used" / remaining, and offer a manual re-arm. */
    val decoyUnlockUsedCount: Int
        get() = try { java.io.File("/data/system/thenile_decoy_state.json").let {
            if (it.exists()) JSONObject(it.readText()).optInt("usedCount", 0) else 0
        } } catch (e: Exception) { 0 }

    fun rearmDecoyOneTimeUnlock() {
        Shell.cmd("rm -f /data/system/thenile_decoy_state.json").exec()
    }

    init {
        ensureSelinuxPolicyPersisted()
        // Ensure system file is initialized
        syncToSystem()
    }

    fun forceSync() {
        syncToSystem()
    }

    /** system_server's app domains (platform_app/system_app/untrusted_app, depending on which one
     *  the hooked process — SystemUI, Calculator, etc. — happens to run as) need read access to
     *  system_data_file to open thenile_config.json, or every config-driven check here silently
     *  falls back to defaults. `magiskpolicy --live` only patches the running kernel policy, so it
     *  vanishes on reboot; a sepolicy.rule under /data/adb/modules is what Magisk actually persists
     *  and reapplies at every boot, so install one (idempotent — skipped once already present) and
     *  also apply it live now so a fresh install works before the next reboot happens.
     */
    private fun ensureSelinuxPolicyPersisted() {
        val rules = listOf(
            "allow platform_app system_data_file file { open read getattr }",
            "allow system_app system_data_file file { open read getattr }",
            "allow untrusted_app system_data_file file { open read getattr }"
        )
        Thread {
            val moduleDir = "/data/adb/modules/nile_sepolicy"
            val ruleFile = "$moduleDir/sepolicy.rule"
            if (!Shell.cmd("test -f $ruleFile").exec().isSuccess) {
                Shell.cmd(
                    "mkdir -p $moduleDir",
                    "touch $moduleDir/module.prop",
                    "echo 'id=nile_sepolicy' >> $moduleDir/module.prop",
                    "echo 'name=Nile SELinux Rules' >> $moduleDir/module.prop",
                    "echo 'version=v1' >> $moduleDir/module.prop",
                    "echo 'versionCode=1' >> $moduleDir/module.prop",
                    "echo 'author=nile' >> $moduleDir/module.prop",
                    "echo 'description=Persists the SELinux rules Nile needs to read its config across reboots.' >> $moduleDir/module.prop",
                    "touch $moduleDir/skip_mount"
                ).exec()
                rules.forEach { rule -> Shell.cmd("echo '$rule' >> $ruleFile").exec() }
            }
            rules.forEach { rule -> Shell.cmd("magiskpolicy --live '$rule'").exec() }
        }.start()
    }

    private fun syncToSystem() {
        val json = JSONObject()
        val pkgs = org.json.JSONArray()
        targetPackages.forEach { pkgs.put(it) }
        json.put("targetPackages", pkgs)
        
        val dirs = org.json.JSONArray()
        targetDirectories.forEach { dirs.put(it) }
        json.put("targetDirectories", dirs)
        
        json.put("SELF_PACKAGE", "com.thenile.vault")

        val codes = org.json.JSONArray()
        decoyCodes.forEach { codes.put(it) }
        json.put("decoyCodes", codes)
        json.put("decoyLockScreenMode", decoyLockScreenMode)
        json.put("decoyUnlockLimit", decoyUnlockLimit)
        // Empty when the feature is off — the hook treats an empty trigger as "never match",
        // so this alone both configures and enables/disables it in one field.
        json.put("calculatorTriggerExpression", if (enableCalculatorDecoy) calculatorTriggerExpression else "")

        // Per-code hide targets, so the lock-screen hook can run the hide itself (via su, straight
        // shell commands) instead of waking Nile's own process — Android's freezer/cold-start
        // policy for a locked, non-foreground app makes that broadcast round-trip unreliable.
        val hideData = org.json.JSONArray()
        decoyCodes.forEach { code ->
            val entry = JSONObject()
            entry.put("code", code)
            val pkgs = org.json.JSONArray()
            getDecoyPackagesForCode(code).forEach { pkgs.put(it) }
            entry.put("packages", pkgs)
            val dirs2 = org.json.JSONArray()
            getDecoyDirectoriesForCode(code).forEach { dirs2.put(it) }
            entry.put("directories", dirs2)
            val dummyArr2 = org.json.JSONArray()
            getDecoyDummyDirectoriesForCode(code).forEach { d ->
                val dObj = JSONObject()
                dObj.put("target", d.target)
                dObj.put("dummy", d.dummy)
                dummyArr2.put(dObj)
            }
            entry.put("dummyDirectories", dummyArr2)
            hideData.put(entry)
        }
        json.put("decoyHideData", hideData)

        val temp = "/data/local/tmp/thenile_cfg.tmp"
        val path = "/data/system/thenile_config.json"
        // Run asynchronously so it doesn't block UI
        Thread {
            Shell.cmd("echo '${json.toString()}' > $temp").exec()
            Shell.cmd("mv $temp $path").exec()
            Shell.cmd("chmod 644 $path").exec()
            // mv preserves the source's SELinux label (shell_data_file, from /data/local/tmp) instead
            // of picking up /data/system's default — system_server's policy then can't open the file
            // at all, silently breaking every config-driven check (this hook's decoy codes/mode,
            // and the existing target-package hiding) while it falls back to hardcoded defaults.
            Shell.cmd("chcon u:object_r:system_data_file:s0 $path").exec()
        }.start()
    }

    companion object {
        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
