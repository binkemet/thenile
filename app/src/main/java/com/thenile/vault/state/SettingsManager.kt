package com.thenile.vault.state

import android.content.Context
import android.content.SharedPreferences
import com.topjohnwu.superuser.Shell
import org.json.JSONArray
import org.json.JSONObject

data class DummyDir(val target: String, val dummy: String, val encrypt: Boolean = false)

data class Vault(
    val id: String,
    var name: String,
    var actionType: String = "switch_user", // "switch_user" or "hide_inplace"
    var targetUserId: Int = 10,
    var decoyPin: String = "",
    var decoyDialerCode: String = "",
    var decoyCalculatorExpression: String = "",
    var packages: List<String> = emptyList(),
    var directories: List<String> = emptyList(),
    var dummyDirectories: List<DummyDir> = emptyList(),
    var files: List<String> = emptyList(),
    // Packages using copy-based snapshot hiding (option B): their real data is snapshotted into the
    // hidden store + wiped, and an anodyne snapshot is restored for the decoy. Not bind-mount like
    // `packages` — see HiddenAppManager. The app itself stays installed; only its data swaps.
    var hiddenApps: List<String> = emptyList(),
    // Packages fully removed for the vault (option A): APK + data snapshotted + `pm uninstall`.
    // Absent entirely in decoy/locked; reinstalled + data restored only on real unlock.
    var uninstallApps: List<String> = emptyList(),
    // Device account names (e.g. a Gmail address) removed via the manual "Remove Accounts Now"
    // action — an admin-triggered action, not a hide/decoy trigger: AccountManager.removeAccount
    // needs an Activity and the account's authenticator generally shows its own confirmation, so
    // it can't run silently on the same path as the root-shell hide/decoy actions.
    var removeAccounts: List<String> = emptyList(),
    var isActive: Boolean = true,
    var hideOnDecoy: Boolean = true
)

open class SettingsManager(private val context: Context) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("vault_settings", Context.MODE_PRIVATE)
    }

    private var _cachedVaults: List<Vault>? = null

    open var vaults: List<Vault>
        get() {
            _cachedVaults?.let { return it }
            val jsonStr = prefs.getString("vaults", null)
            if (jsonStr == null) {
                // Migration from old flat list
                val oldPkgs = prefs.getString("targetPackages", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                val oldDirs = prefs.getString("targetDirectories", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

                val defaultList = listOf(
                    Vault(
                        id = "default",
                        name = "Default Vault",
                        actionType = "switch_user",
                        targetUserId = 10,
                        decoyPin = "1234",
                        decoyDialerCode = "1234",
                        decoyCalculatorExpression = "47-87+23",
                        packages = oldPkgs,
                        directories = oldDirs,
                        dummyDirectories = emptyList(),
                        files = emptyList(),
                        isActive = true,
                        hideOnDecoy = true
                    )
                )
                _cachedVaults = defaultList
                return defaultList
            }
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Vault>()
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
                val fileList = mutableListOf<String>()
                val filesArr = obj.optJSONArray("files")
                if (filesArr != null) {
                    for (j in 0 until filesArr.length()) fileList.add(filesArr.getString(j))
                }
                val hiddenAppList = mutableListOf<String>()
                obj.optJSONArray("hiddenApps")?.let { for (j in 0 until it.length()) hiddenAppList.add(it.getString(j)) }
                val uninstallAppList = mutableListOf<String>()
                obj.optJSONArray("uninstallApps")?.let { for (j in 0 until it.length()) uninstallAppList.add(it.getString(j)) }
                val removeAccountList = mutableListOf<String>()
                obj.optJSONArray("removeAccounts")?.let { for (j in 0 until it.length()) removeAccountList.add(it.getString(j)) }
                val rawPin = obj.optString("decoyPin", if (obj.optBoolean("hideOnDecoy", false)) "1234" else "")
                list.add(Vault(
                    id = obj.optString("id"),
                    name = obj.optString("name", "Unnamed Vault"),
                    actionType = obj.optString("actionType", if (obj.optInt("targetUserId", 0) > 0) "switch_user" else "hide_inplace"),
                    targetUserId = obj.optInt("targetUserId", 10),
                    decoyPin = rawPin,
                    decoyDialerCode = obj.optString("decoyDialerCode", rawPin),
                    decoyCalculatorExpression = obj.optString("decoyCalculatorExpression", ""),
                    packages = pkgs,
                    directories = dirs,
                    dummyDirectories = dummyDirs,
                    files = fileList,
                    hiddenApps = hiddenAppList,
                    uninstallApps = uninstallAppList,
                    removeAccounts = removeAccountList,
                    isActive = obj.optBoolean("isActive", true),
                    hideOnDecoy = obj.optBoolean("hideOnDecoy", true)
                ))
            }
            _cachedVaults = list
            return list
        }
        set(value) {
            _cachedVaults = value
            val array = JSONArray()
            value.forEach { p ->
                val obj = JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                obj.put("actionType", p.actionType)
                obj.put("targetUserId", p.targetUserId)
                obj.put("decoyPin", p.decoyPin)
                obj.put("decoyDialerCode", p.decoyDialerCode)
                obj.put("decoyCalculatorExpression", p.decoyCalculatorExpression)
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
                val filesArr = JSONArray()
                p.files.forEach { filesArr.put(it) }
                obj.put("files", filesArr)
                val hiddenAppsArr = JSONArray()
                p.hiddenApps.forEach { hiddenAppsArr.put(it) }
                obj.put("hiddenApps", hiddenAppsArr)
                val uninstallAppsArr = JSONArray()
                p.uninstallApps.forEach { uninstallAppsArr.put(it) }
                obj.put("uninstallApps", uninstallAppsArr)
                val removeAccountsArr = JSONArray()
                p.removeAccounts.forEach { removeAccountsArr.put(it) }
                obj.put("removeAccounts", removeAccountsArr)
                obj.put("isActive", p.isActive)
                obj.put("hideOnDecoy", p.hideOnDecoy)
                array.put(obj)
            }
            prefs.edit().putString("vaults", array.toString()).commit()
            syncToSystem()
        }

    val targetPackages: List<String>
        get() = vaults.filter { it.isActive }.flatMap { it.packages }.distinct()

    val targetDirectories: List<String>
        get() = vaults.filter { it.isActive }.flatMap { it.directories }.distinct()
        
    val targetDummyDirectories: List<DummyDir>
        get() = vaults.filter { it.isActive }.flatMap { it.dummyDirectories }.distinct()

    val targetFiles: List<String>
        get() = vaults.filter { it.isActive }.flatMap { it.files }.distinct()

    val decoyPackages: List<String>
        get() = vaults.filter { it.hideOnDecoy }.flatMap { it.packages }.distinct()
        
    val decoyDirectories: List<String>
        get() = vaults.filter { it.hideOnDecoy }.flatMap { it.directories }.distinct()
        
    val decoyDummyDirectories: List<DummyDir>
        get() = vaults.filter { it.hideOnDecoy }.flatMap { it.dummyDirectories }.distinct()

    val decoyFiles: List<String>
        get() = vaults.filter { it.hideOnDecoy }.flatMap { it.files }.distinct()

    fun getVaultForDecoyPin(pin: String): Vault? {
        if (pin.isBlank()) return null
        return vaults.firstOrNull { it.isActive && it.decoyPin == pin }
    }

    fun getVaultForDialerCode(code: String): Vault? {
        if (code.isBlank()) return null
        return vaults.firstOrNull { it.isActive && (it.decoyDialerCode == code || it.decoyPin == code) }
    }

    fun getVaultForCalculatorExpr(expr: String): Vault? {
        if (expr.isBlank()) return null
        val norm = expr.removeSuffix("=").trim()
        return vaults.firstOrNull { it.isActive && (it.decoyCalculatorExpression.removeSuffix("=").trim() == norm || it.decoyPin == norm) }
    }

    fun getDecoyPackagesForCode(code: String): List<String> {
        return vaults.filter { it.decoyPin == code || (code == codeDecoy && it.decoyPin.isNotBlank()) }
            .flatMap { it.packages }.distinct()
    }

    fun getDecoyDirectoriesForCode(code: String): List<String> {
        return vaults.filter { it.decoyPin == code || (code == codeDecoy && it.decoyPin.isNotBlank()) }
            .flatMap { it.directories }.distinct()
    }

    fun getDecoyDummyDirectoriesForCode(code: String): List<DummyDir> {
        return vaults.filter { it.decoyPin == code || (code == codeDecoy && it.decoyPin.isNotBlank()) }
            .flatMap { it.dummyDirectories }.distinct()
    }

    fun getDecoyFilesForCode(code: String): List<String> {
        return vaults.filter { it.decoyPin == code || (code == codeDecoy && it.decoyPin.isNotBlank()) }
            .flatMap { it.files }.distinct()
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

    /** Dead man's switch: if the REAL vault hasn't been unlocked in [deadManSwitchHours], the
     *  selected vaults ([deadManSwitchVaultIds]) get hidden automatically — see DeadManSwitch.kt. */
    var deadManSwitchEnabled: Boolean
        get() = prefs.getBoolean("deadManSwitchEnabled", false)
        set(value) { prefs.edit().putBoolean("deadManSwitchEnabled", value).commit() }

    var deadManSwitchHours: Int
        get() = prefs.getInt("deadManSwitchHours", 72)
        set(value) { prefs.edit().putInt("deadManSwitchHours", value).commit() }

    var deadManSwitchVaultIds: Set<String>
        get() = prefs.getStringSet("deadManSwitchVaultIds", emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet("deadManSwitchVaultIds", value).commit() }

    /** Wrong-PIN switch: N consecutive wrong real-vault PIN entries hides the selected vaults —
     *  see WrongPinSwitch.kt. Independent of the dead man's switch (different trigger, same action). */
    var wrongPinSwitchEnabled: Boolean
        get() = prefs.getBoolean("wrongPinSwitchEnabled", false)
        set(value) { prefs.edit().putBoolean("wrongPinSwitchEnabled", value).commit() }

    var wrongPinSwitchLimit: Int
        get() = prefs.getInt("wrongPinSwitchLimit", 5)
        set(value) { prefs.edit().putInt("wrongPinSwitchLimit", value).commit() }

    var wrongPinSwitchVaultIds: Set<String>
        get() = prefs.getStringSet("wrongPinSwitchVaultIds", emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet("wrongPinSwitchVaultIds", value).commit() }

    /** USB switch: hides the selected vaults every time the phone is plugged in via USB —
     *  see UsbPluggedSwitch.kt. Binary event, no threshold needed. */
    var usbSwitchEnabled: Boolean
        get() = prefs.getBoolean("usbSwitchEnabled", false)
        set(value) { prefs.edit().putBoolean("usbSwitchEnabled", value).commit() }

    var usbSwitchVaultIds: Set<String>
        get() = prefs.getStringSet("usbSwitchVaultIds", emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet("usbSwitchVaultIds", value).commit() }

    /** "off" | "fake_wrong_pin" | "one_time_unlock" | "switch_user". Default off: this hooks the real Android
     *  keyguard, so it stays inert until explicitly enabled. */
    var decoyLockScreenMode: String
        get() = prefs.getString("decoyLockScreenMode", "off") ?: "off"
        set(value) { prefs.edit().putString("decoyLockScreenMode", value).commit() ; syncToSystem() }

    /** Decoy Android secondary user vault ID to switch into and hide from user lists. -1 = unset. */
    var decoyUserId: Int
        get() = prefs.getInt("decoyUserId", -1)
        set(value) { prefs.edit().putInt("decoyUserId", value).commit(); syncToSystem() }

    /** Whether the admin has acknowledged the one-time "test your vault hiding once" warning. */
    var hideTestWarningAck: Boolean
        get() = prefs.getBoolean("hideTestWarningAck", false)
        set(value) { prefs.edit().putBoolean("hideTestWarningAck", value).commit() }

    /** Whether to suppress the full-screen 'Switching to Decoy...' dialog and animation when switching vaults. */
    var suppressUserSwitchAnimation: Boolean
        get() = prefs.getBoolean("suppressUserSwitchAnimation", true)
        set(value) { prefs.edit().putBoolean("suppressUserSwitchAnimation", value).commit(); syncToSystem() }

    /** Whether to hide the user switcher icon in Quick Settings / Notifications. */
    var hideUserSwitcherInQuickSettings: Boolean
        get() = prefs.getBoolean("hideUserSwitcherInQuickSettings", true)
        set(value) { prefs.edit().putBoolean("hideUserSwitcherInQuickSettings", value).commit(); syncToSystem() }

    /** Whether to hide the 'Users' / 'Multiple users' section and avatar in Android Settings. */
    var hideUserSwitcherInSettings: Boolean
        get() = prefs.getBoolean("hideUserSwitcherInSettings", true)
        set(value) { prefs.edit().putBoolean("hideUserSwitcherInSettings", value).commit(); syncToSystem() }

    /** Persisted SAF tree URI (content://...tree/...) SoftVault stores encrypted blobs under when
     *  there's no root/Shizuku CAP_SYS_ADMIN for a real vault mount. Null = Nile's own private
     *  storage (the default, and the only option that's invisible to every other app by itself —
     *  see the warning shown before this can be changed, in AdminActivity). Not synced to the
     *  Xposed config file: only SoftVault (in-app, non-root path) ever reads it. */
    var softVaultDirectoryUri: String?
        get() = prefs.getString("softVaultDirectoryUri", null)
        set(value) { prefs.edit().putString("softVaultDirectoryUri", value).apply() }

    /** All codes that should trigger the decoy: the global code plus every vault's own. */
    val decoyCodes: List<String>
        get() = (listOf(codeDecoy) + vaults.mapNotNull { it.decoyPin.ifBlank { null } }).distinct()

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
        try {
            val rules = listOf(
                "allow platform_app system_data_file file { open read getattr }",
                "allow system_app system_data_file file { open read getattr }",
                "allow untrusted_app system_data_file file { open read getattr }"
            )
            Thread {
                try {
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
                } catch (e: Throwable) {
                    // Ignore in unit test environment
                }
            }.start()
        } catch (e: Throwable) {
            // Ignore in unit test environment
        }
    }

    open fun syncToSystem() {
        try {
            val json = JSONObject()
            val pkgs = org.json.JSONArray()
            targetPackages.forEach { pkgs.put(it) }
            json.put("targetPackages", pkgs)
            
            val dirs = org.json.JSONArray()
            targetDirectories.forEach { dirs.put(it) }
            json.put("targetDirectories", dirs)

            val files = org.json.JSONArray()
            targetFiles.forEach { files.put(it) }
            json.put("targetFiles", files)
            
            json.put("SELF_PACKAGE", "com.thenile.vault")

            val codes = org.json.JSONArray()
            decoyCodes.forEach { codes.put(it) }
            json.put("decoyCodes", codes)

            val master = org.json.JSONArray()
            listOf("8888", codeUnlock, codeAdmin, codeLock).distinct().filter { it.isNotBlank() }.forEach { master.put(it) }
            json.put("masterCodes", master)
            json.put("codeLock", codeLock)
            json.put("codeDecoy", codeDecoy)
            json.put("codeUnlock", codeUnlock)
            json.put("codeAdmin", codeAdmin)

            json.put("decoyLockScreenMode", decoyLockScreenMode)
            json.put("decoyUnlockLimit", decoyUnlockLimit)
            json.put("decoyUserId", decoyUserId)
            json.put("suppressUserSwitchAnimation", suppressUserSwitchAnimation)
            json.put("hideUserSwitcherInQuickSettings", hideUserSwitcherInQuickSettings)
            json.put("hideUserSwitcherInSettings", hideUserSwitcherInSettings)
            json.put("enableCalculatorDecoy", enableCalculatorDecoy)
            // Empty when the feature is off — the hook treats an empty trigger as "never match",
            // so this alone both configures and enables/disables it in one field.
            json.put("calculatorTriggerExpression", if (enableCalculatorDecoy) calculatorTriggerExpression else "")

            // Full vaults array for dynamic multi-tiered decoy evaluation
            val vaultsArr = org.json.JSONArray()
            vaults.forEach { p ->
                val pObj = JSONObject()
                pObj.put("id", p.id)
                pObj.put("name", p.name)
                pObj.put("actionType", p.actionType)
                pObj.put("targetUserId", p.targetUserId)
                pObj.put("decoyPin", p.decoyPin)
                pObj.put("decoyDialerCode", p.decoyDialerCode)
                pObj.put("decoyCalculatorExpression", p.decoyCalculatorExpression)
                pObj.put("isActive", p.isActive)
                pObj.put("hideOnDecoy", p.hideOnDecoy)

                val pPkgs = org.json.JSONArray()
                p.packages.forEach { pPkgs.put(it) }
                pObj.put("packages", pPkgs)

                val pDirs = org.json.JSONArray()
                p.directories.forEach { pDirs.put(it) }
                pObj.put("directories", pDirs)

                val pFiles = org.json.JSONArray()
                p.files.forEach { pFiles.put(it) }
                pObj.put("files", pFiles)

                val pDummies = org.json.JSONArray()
                p.dummyDirectories.forEach { d ->
                    val dObj = JSONObject()
                    dObj.put("target", d.target)
                    dObj.put("dummy", d.dummy)
                    dObj.put("encrypt", d.encrypt)
                    pDummies.put(dObj)
                }
                pObj.put("dummyDirectories", pDummies)

                vaultsArr.put(pObj)
            }
            json.put("vaults", vaultsArr)

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
                val files2 = org.json.JSONArray()
                getDecoyFilesForCode(code).forEach { files2.put(it) }
                entry.put("files", files2)
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
                try {
                    Shell.cmd("echo '${json.toString()}' > $temp").exec()
                    Shell.cmd("mv $temp $path").exec()
                    Shell.cmd("chmod 644 $path").exec()
                    // mv preserves the source's SELinux label (shell_data_file, from /data/local/tmp) instead
                    // of picking up /data/system's default — system_server's policy then can't open the file
                    // at all, silently breaking every config-driven check (this hook's decoy codes/mode,
                    // and the existing target-package hiding) while it falls back to hardcoded defaults.
                    Shell.cmd("chcon u:object_r:system_data_file:s0 $path").exec()
                } catch (e: Throwable) {
                    // Ignore in unit test environment
                }
            }.start()
        } catch (e: Throwable) {
            // Ignore in unit test environment
        }
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
