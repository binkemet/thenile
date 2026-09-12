package com.thenile.vault.xposed

import android.annotation.SuppressLint
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Hides Config.TARGET_PACKAGES from package queries while the vault is not UNLOCKED.
 *
 * Two injection points:
 *  - onPackageLoaded: the in-process android.app.ApplicationPackageManager (hides from the app
 *    doing the query, e.g. a launcher's own PM calls).
 *  - onSystemServerStarting: PackageManagerService / ComputerEngine inside system_server, which is
 *    the authoritative source every app and Settings ultimately queries. THIS is what hides
 *    system-wide. It must be installed from onSystemServerStarting (with the system_server
 *    classloader) — onPackageLoaded("android") is the legacy Xposed pattern and does not fire for
 *    system_server under libxposed.
 */
class PackageManagerHook : XposedModule() {

    private var cachedHidden = setOf("com.thenile.vault")
    private var cachedSelf = "com.thenile.vault"
    private var cachedDecoyUserId = -1
    private var lastCheck = 0L

    private fun refreshConfigIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastCheck <= 5000) return
        lastCheck = now
        try {
            val file = java.io.File("/data/system/thenile_config.json")
            if (file.exists()) {
                val json = org.json.JSONObject(com.thenile.vault.root.ConfigCrypto.decrypt(file.readText()))
                val pkgs = json.optJSONArray("targetPackages")
                val self = json.optString("SELF_PACKAGE", "com.thenile.vault")
                val list = mutableListOf(self)
                if (pkgs != null) {
                    for (i in 0 until pkgs.length()) {
                        list.add(pkgs.getString(i))
                    }
                }
                cachedHidden = list.toSet()
                cachedSelf = self
                cachedDecoyUserId = json.optInt("decoyUserId", -1)
            }
        } catch (e: Exception) {}
    }

    private fun decoyUserId(): Int {
        refreshConfigIfStale()
        return cachedDecoyUserId
    }

    private fun getUserIdFromObject(item: Any?): Int? {
        if (item == null) return null
        return try {
            val field = item.javaClass.getField("id")
            field.getInt(item)
        } catch (e: Exception) {
            try {
                val method = item.javaClass.getMethod("getIdentifier")
                method.invoke(item) as? Int
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun isSystemOrTelephonyCaller(): Boolean {
        val callingUid = android.os.Binder.getCallingUid()
        // 0: root, 1000: system_server, 1001: telephony/phone, 1002: bluetooth, 1073: network stack
        return callingUid == 0 || callingUid == 1000 || callingUid == 1001 || callingUid == 1002 || callingUid == 1073 || callingUid < 10000
    }

    /** Real check for "is the CURRENT process system_server", unlike a bare
     *  packageName=="android" comparison in onPackageLoaded (see its call site). */
    private fun isActuallySystemServerProcess(): Boolean = try {
        android.os.Process.myUid() == 1000 &&
            (Class.forName("android.app.ActivityThread").getMethod("currentProcessName").invoke(null) as? String) == "system_server"
    } catch (e: Exception) {
        false
    }

    private fun shouldFilterDecoyUser(decoyId: Int): Boolean {
        if (decoyId < 0 || isUnlocked()) return false
        if (isSystemOrTelephonyCaller()) return false
        val currentUserId = try {
            val clazz = Class.forName("android.app.ActivityManager")
            clazz.getMethod("getCurrentUser").invoke(null) as? Int ?: 0
        } catch (e: Exception) {
            0
        }
        if (currentUserId == decoyId) return false
        return true
    }

    private fun isHideUserSwitcherInQuickSettingsEnabled(): Boolean = try {
        val json = readConfigJsonObject() ?: return true
        json.optBoolean("hideUserSwitcherInQuickSettings", true)
    } catch (e: Exception) {
        true
    }

    private fun isHideUserSwitcherInSettingsEnabled(): Boolean = try {
        val json = readConfigJsonObject() ?: return true
        json.optBoolean("hideUserSwitcherInSettings", true)
    } catch (e: Exception) {
        true
    }

    private fun shouldFilterDecoyUserInSettings(decoyId: Int): Boolean {
        if (!isHideUserSwitcherInSettingsEnabled()) return false
        if (decoyId < 0 || isUnlocked()) return false
        val currentUserId = try {
            val clazz = Class.forName("android.app.ActivityManager")
            clazz.getMethod("getCurrentUser").invoke(null) as? Int ?: 0
        } catch (e: Exception) {
            0
        }
        if (currentUserId == decoyId) return false
        return true
    }

    private fun shouldFilterDecoyUserInQuickSettings(decoyId: Int): Boolean {
        if (!isHideUserSwitcherInQuickSettingsEnabled()) return false
        if (decoyId < 0 || isUnlocked()) return false
        val currentUserId = try {
            val clazz = Class.forName("android.app.ActivityManager")
            clazz.getMethod("getCurrentUser").invoke(null) as? Int ?: 0
        } catch (e: Exception) {
            0
        }
        if (currentUserId == decoyId) return false
        return true
    }

    /** Hidden from enumeration (getInstalledPackages/queryIntentActivities) — includes self, so the
     *  launcher doesn't show the app's icon. */
    private val hidden: Set<String>
        get() { refreshConfigIfStale(); return cachedHidden }

    /** Hidden from DIRECT getPackageInfo/getApplicationInfo lookups — excludes self. Direct lookups
     *  are what Android's own internals use to resolve and launch a component (activity/broadcast
     *  dispatch); hiding "com.thenile.vault" from THIS path made the app unable to bootstrap itself
     *  while locked — any external trigger (dial code, tile, broadcast) would crash on launch with
     *  "package not installed". Enumeration hiding (icon/list) doesn't need this, only direct
     *  lookups by the app's own package name do. */
    private val hiddenDirect: Set<String>
        get() { refreshConfigIfStale(); return cachedHidden - cachedSelf }
    private val pmMethods = setOf("getInstalledPackages", "getInstalledApplications", "queryIntentActivities")

    private fun isUnlocked(): Boolean = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, com.thenile.vault.Config.STATE_PROP, "LOCKED") as String) == "UNLOCKED"
    } catch (e: Exception) {
        false
    }

    private fun pkgNameOf(item: Any?): String? = when (item) {
        is android.content.pm.PackageInfo -> item.packageName
        is android.content.pm.ApplicationInfo -> item.packageName
        is android.content.pm.ResolveInfo -> item.activityInfo?.packageName ?: item.serviceInfo?.packageName
        else -> null
    }

    /** Packages for a uid, resolved WITHOUT a Binder round-trip back into PackageManagerService.
     *  Called from a hook installed directly on PMS/ComputerEngine on every getInstalledPackages /
     *  queryIntentActivities / getPackageInfo call system-wide — going through
     *  Context.packageManager here re-enters the same service via IPC on a different binder
     *  thread while the current call may still be holding PMS's internal lock, which can stall or
     *  deadlock the binder thread pool under load (symptoms: mobile data / telephony binder calls
     *  hanging, other apps' PM queries timing out). Calling getPackagesForUid reflectively on the
     *  already-in-hand PMS/ComputerEngine instance stays on the current thread and skips IPC
     *  entirely. Falls back to the Context-based lookup only when no instance is available (the
     *  in-process launcher hook path, which isn't on this hot Binder path). */
    private fun packagesForUid(callingUid: Int, pmsInstance: Any?): Array<out String>? {
        if (pmsInstance != null) {
            try {
                val method = pmsInstance.javaClass.methods.firstOrNull {
                    it.name == "getPackagesForUid" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                if (method != null) {
                    @Suppress("UNCHECKED_CAST")
                    val result = method.invoke(pmsInstance, callingUid) as? Array<out String>
                    if (result != null) return result
                }
            } catch (e: Exception) {
                Log.w("NileHook", "packagesForUid: local lookup on ${pmsInstance.javaClass.simpleName} failed: ${e.message}")
            }
        }
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentApp = atClass.getMethod("currentApplication").invoke(null) as? android.content.Context
            currentApp?.packageManager?.getPackagesForUid(callingUid)
        } catch (e: Exception) {
            null
        }
    }

    private fun isXposedOrRootManager(callingUid: Int, pmsInstance: Any? = null): Boolean {
        if (callingUid == 0 || callingUid == 1000 || callingUid == 1001 || callingUid == 1002 || callingUid == 1073) return true
        val packages = packagesForUid(callingUid, pmsInstance)
        return packages?.any { pkg ->
            pkg in XPOSED_AND_ROOT_MANAGERS ||
                pkg.contains("lsposed", ignoreCase = true) ||
                pkg.contains("vector", ignoreCase = true) ||
                pkg.contains("xposed", ignoreCase = true) ||
                pkg.contains("magisk", ignoreCase = true) ||
                pkg.contains("kernelsu", ignoreCase = true)
        } ?: false
    }

    /** Strip hidden packages from a returned List or ParceledListSlice; pass through when unlocked. */
    private fun filterResult(parceledClass: Class<*>?, result: Any?, pmsInstance: Any? = null): Any? {
        if (result == null || isUnlocked()) return result
        val callingUid = android.os.Binder.getCallingUid()
        if (isXposedOrRootManager(callingUid, pmsInstance)) return result
        if (parceledClass != null && parceledClass.isInstance(result)) {
            return try {
                val list = parceledClass.getMethod("getList").invoke(result) as? List<*> ?: return result
                val kept = list.filter { pkgNameOf(it) !in hidden }
                val ctor = try {
                    parceledClass.getConstructor(List::class.java)
                } catch (e: Exception) {
                    parceledClass.getDeclaredConstructor(List::class.java).also { it.isAccessible = true }
                }
                ctor.newInstance(kept)
            } catch (e: Exception) {
                result
            }
        }
        return if (result is List<*>) result.filter { pkgNameOf(it) !in hidden } else result
    }

    private fun hookPm(clazz: Class<*>, parceledClass: Class<*>?) {
        clazz.declaredMethods.filter { it.name in pmMethods }.forEach { method ->
            hook(method).intercept { chain -> filterResult(parceledClass, chain.proceed(), chain.getThisObject()) }
        }
    }

    /** The requested package name from a getPackageInfo/getApplicationInfo call's first arg. */
    private fun requestedPkg(arg0: Any?): String? = when (arg0) {
        is String -> arg0
        // VersionedPackage and similar carry getPackageName().
        else -> try { arg0?.javaClass?.getMethod("getPackageName")?.invoke(arg0) as? String } catch (e: Exception) { null }
    }

    /**
     * Hide a direct getPackageInfo/getApplicationInfo for a locked target.
     * @param throwOnMiss true for ApplicationPackageManager (declares NameNotFoundException);
     *   false for PMS/ComputerEngine (return null — they do not declare the checked exception).
     */
    private fun hookDirectQueries(clazz: Class<*>, throwOnMiss: Boolean) {
        clazz.declaredMethods.filter { it.name == "getPackageInfo" || it.name == "getApplicationInfo" }.forEach { method ->
            hook(method).intercept { chain ->
                val callingUid = android.os.Binder.getCallingUid()
                if (isXposedOrRootManager(callingUid, chain.getThisObject())) {
                    return@intercept chain.proceed()
                }
                val name = requestedPkg(chain.getArg(0))
                if (!isUnlocked() && name != null && name in hiddenDirect) {
                    if (throwOnMiss) throw android.content.pm.PackageManager.NameNotFoundException(name)
                    return@intercept null
                }
                chain.proceed()
            }
        }
    }

    @SuppressLint("NewApi")
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        Log.i("NileHook", "DIAG onPackageLoaded pkg=${param.packageName} uid=${android.os.Process.myUid()} isSystemServer=${isActuallySystemServerProcess()}")
        if (param.packageName in SYSTEM_NETWORK_PACKAGES) {
            return
        }
        // packageName=="android" alone is NOT a reliable signal for "this is system_server" on
        // this platform — confirmed on-device: it also fires inside every other scoped process
        // (e.g. com.android.systemui), since the framework's own "android" classes are loaded
        // there too. Without this check, hookSystemServer() resolves PackageManagerService et al.
        // via services.jar loaded into e.g. SystemUI's own classloader — a phantom copy, fully
        // disconnected from the real instance running inside system_server, so every hook attached
        // there is a silent no-op. onSystemServerStarting is the real, authoritative entrypoint
        // (see its own override below); this stays only as a fallback for builds where that never
        // fires, gated so it can't misfire into the wrong process.
        if (param.packageName == "android" && isActuallySystemServerProcess()) {
            hookSystemServer(param.defaultClassLoader)
        }
        if (param.packageName == "android" || param.packageName == "com.android.systemui") {
            hookLockCredential(param.defaultClassLoader)
            hookWrongPinMessage(param.defaultClassLoader)
        }
        if (param.packageName == "com.android.settings") {
            hookSettingsApp(param.defaultClassLoader)
            return
        }
        if (param.packageName in CALCULATOR_PACKAGES) {
            hookCalculatorTrigger(param.defaultClassLoader)
            return
        }
        if (param.packageName in DIALER_PACKAGES) {
            hookDialerTrigger(param.defaultClassLoader)
            return
        }
        if (param.packageName in setOf("com.android.providers.media", "com.android.providers.media.module", "com.google.android.providers.media.module")) {
            hookMediaProvider(param.defaultClassLoader)
            return
        }

        // Only hook in-process PM for known launchers; never hook arbitrary system/telephony/carrier daemons
        if (param.packageName in KNOWN_LAUNCHERS) {
            try {
                val appPm = param.defaultClassLoader.loadClass("android.app.ApplicationPackageManager")
                hookPm(appPm, null)
                hookDirectQueries(appPm, throwOnMiss = true)
                Log.i("NileHook", "ApplicationPackageManager hooked in launcher ${param.packageName}")
            } catch (e: Exception) {
                Log.e("NileHook", "in-process PM hook failed in ${param.packageName}", e)
            }
        }
    }

    private fun shouldFilterSwitcherInProcess(decoyId: Int): Boolean {
        val procName = try {
            val clazz = Class.forName("android.app.ActivityThread")
            clazz.getMethod("currentProcessName").invoke(null) as? String
        } catch (e: Exception) { null }
        if (procName == "com.android.settings") {
            return shouldFilterDecoyUserInSettings(decoyId)
        }
        return shouldFilterDecoyUserInQuickSettings(decoyId)
    }

    private fun hookSettingsApp(cl: ClassLoader) {
        try {
            val capabilityNames = listOf(
                "com.android.settings.users.UserCapabilities",
                "com.android.settingslib.users.UserCapabilities"
            )
            for (name in capabilityNames) {
                try {
                    val clazz = cl.loadClass(name)
                    for (m in clazz.declaredMethods) {
                        if (m.returnType == Boolean::class.javaPrimitiveType) {
                            hook(m).intercept { chain ->
                                val decoyId = decoyUserId()
                                if (shouldFilterDecoyUserInSettings(decoyId)) {
                                    if (m.name in setOf("isUserSwitcherEnabled", "isMultipleUsersSupported", "isEnabled", "canAddUsers", "canAddMoreUsers")) {
                                        return@intercept false
                                    }
                                }
                                chain.proceed()
                            }
                            Log.i("NileHook", "Hooked $name.${m.name}")
                        }
                    }
                } catch (e: Exception) {}
            }

            val controllerNames = listOf(
                "com.android.settings.users.UserSettingsPreferenceController",
                "com.android.settings.users.MultiUserPreferenceController"
            )
            for (cName in controllerNames) {
                try {
                    val cClass = cl.loadClass(cName)
                    for (m in cClass.declaredMethods) {
                        if (m.name in setOf("getAvailabilityStatus", "isAvailable")) {
                            hook(m).intercept { chain ->
                                val decoyId = decoyUserId()
                                if (shouldFilterDecoyUserInSettings(decoyId)) {
                                    if (m.returnType == Int::class.javaPrimitiveType) {
                                        return@intercept 3 // UNSUPPORTED_ON_DEVICE
                                    } else if (m.returnType == Boolean::class.javaPrimitiveType) {
                                        return@intercept false
                                    }
                                }
                                chain.proceed()
                            }
                            Log.i("NileHook", "Hooked $cName.${m.name}")
                        }
                    }
                } catch (e: Exception) {}
            }

            try {
                val umClass = cl.loadClass("android.os.UserManager")
                for (m in umClass.declaredMethods) {
                    if (m.name in setOf("supportsMultipleUsers", "canAddMoreUsers", "isUserSwitcherEnabled")) {
                        hook(m).intercept { chain ->
                            val decoyId = decoyUserId()
                            if (shouldFilterDecoyUserInSettings(decoyId)) {
                                return@intercept false
                            }
                            chain.proceed()
                        }
                    } else if (m.name == "getMaxSupportedUsers") {
                        hook(m).intercept { chain ->
                            val decoyId = decoyUserId()
                            if (shouldFilterDecoyUserInSettings(decoyId)) {
                                return@intercept 1
                            }
                            chain.proceed()
                        }
                    }
                }
            } catch (e: Exception) {}

            val prefGroupClassNames = listOf("androidx.preference.PreferenceGroup", "android.preference.PreferenceGroup")
            for (pgName in prefGroupClassNames) {
                try {
                    val pgClass = cl.loadClass(pgName)
                    for (m in pgClass.declaredMethods.filter { it.name == "addPreference" }) {
                        hook(m).intercept { chain ->
                            val decoyId = decoyUserId()
                            if (shouldFilterDecoyUserInSettings(decoyId)) {
                                val pref = chain.args.getOrNull(0)
                                if (pref != null) {
                                    val key = try {
                                        pref.javaClass.getMethod("getKey").invoke(pref) as? String
                                    } catch (e: Exception) { null }
                                    val title = try {
                                        (pref.javaClass.getMethod("getTitle").invoke(pref) as? CharSequence)?.toString()
                                    } catch (e: Exception) { null }
                                    if (key in setOf("user_settings", "multiple_users", "users", "multi_user") ||
                                        title?.equals("Users", ignoreCase = true) == true ||
                                        title?.equals("Multiple users", ignoreCase = true) == true) {
                                        Log.i("NileHook", "Blocked $pgName.addPreference for key=$key title=$title")
                                        return@intercept false
                                    }
                                }
                            }
                            chain.proceed()
                        }
                    }
                    Log.i("NileHook", "Hooked $pgName.addPreference")
                } catch (e: Exception) {}
            }

            val prefClassNames = listOf("androidx.preference.Preference", "android.preference.Preference")
            for (pName in prefClassNames) {
                try {
                    val pClass = cl.loadClass(pName)
                    pClass.declaredMethods.filter { it.name == "isVisible" }.forEach { m ->
                        hook(m).intercept { chain ->
                            val decoyId = decoyUserId()
                            if (shouldFilterDecoyUserInSettings(decoyId)) {
                                val pref = chain.getThisObject()
                                val key = try {
                                    pref.javaClass.getMethod("getKey").invoke(pref) as? String
                                } catch (e: Exception) { null }
                                val title = try {
                                    (pref.javaClass.getMethod("getTitle").invoke(pref) as? CharSequence)?.toString()
                                } catch (e: Exception) { null }
                                if (key in setOf("user_settings", "multiple_users", "users", "multi_user") ||
                                    title?.equals("Users", ignoreCase = true) == true ||
                                    title?.equals("Multiple users", ignoreCase = true) == true) {
                                    return@intercept false
                                }
                            }
                            chain.proceed()
                        }
                    }

                    pClass.declaredMethods.filter { it.name == "setVisible" }.forEach { m ->
                        hook(m).intercept { chain ->
                            val decoyId = decoyUserId()
                            if (shouldFilterDecoyUserInSettings(decoyId)) {
                                val pref = chain.getThisObject()
                                val key = try {
                                    pref.javaClass.getMethod("getKey").invoke(pref) as? String
                                } catch (e: Exception) { null }
                                val title = try {
                                    (pref.javaClass.getMethod("getTitle").invoke(pref) as? CharSequence)?.toString()
                                } catch (e: Exception) { null }
                                if (key in setOf("user_settings", "multiple_users", "users", "multi_user") ||
                                    title?.equals("Users", ignoreCase = true) == true ||
                                    title?.equals("Multiple users", ignoreCase = true) == true) {
                                    val visibleArg = chain.args.getOrNull(0) as? Boolean ?: true
                                    if (visibleArg) {
                                        return@intercept null
                                    }
                                }
                            }
                            chain.proceed()
                        }
                    }

                    pClass.declaredMethods.filter { it.name == "onBindViewHolder" }.forEach { m ->
                        hook(m).intercept { chain ->
                            val result = chain.proceed()
                            val decoyId = decoyUserId()
                            if (shouldFilterDecoyUserInSettings(decoyId)) {
                                val pref = chain.getThisObject()
                                val key = try {
                                    pref.javaClass.getMethod("getKey").invoke(pref) as? String
                                } catch (e: Exception) { null }
                                val title = try {
                                    (pref.javaClass.getMethod("getTitle").invoke(pref) as? CharSequence)?.toString()
                                } catch (e: Exception) { null }
                                if (key in setOf("user_settings", "multiple_users", "users", "multi_user") ||
                                    title?.equals("Users", ignoreCase = true) == true ||
                                    title?.equals("Multiple users", ignoreCase = true) == true) {
                                    val holder = chain.args.getOrNull(0)
                                    if (holder != null) {
                                        try {
                                            val itemView = holder.javaClass.getField("itemView").get(holder) as? android.view.View
                                            itemView?.visibility = android.view.View.GONE
                                            itemView?.layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
                                        } catch (e: Exception) {}
                                    }
                                }
                            }
                            result
                        }
                    }
                    Log.i("NileHook", "Hooked $pName methods (isVisible, setVisible, onBindViewHolder)")
                } catch (e: Exception) {}
            }

            try {
                val mixinClass = cl.loadClass("com.android.settings.accounts.AvatarViewMixin")
                for (m in (mixinClass.methods.toList() + mixinClass.declaredMethods.toList()).distinct()) {
                    if (m.name in setOf("onStateChanged", "display", "updateAvatar")) {
                        try {
                            hook(m).intercept { chain ->
                                val decoyId = decoyUserId()
                                if (shouldFilterDecoyUserInSettings(decoyId)) {
                                    return@intercept null
                                }
                                chain.proceed()
                            }
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}

            try {
                val homepageClass = cl.loadClass("com.android.settings.homepage.SettingsHomepageActivity")
                for (m in (homepageClass.methods.toList() + homepageClass.declaredMethods.toList()).distinct()) {
                    if (m.name in setOf("onCreate", "onStart", "onResume", "onPostResume", "onAttachedToWindow", "updateAvatar", "initAvatarView")) {
                        try {
                            hook(m).intercept { chain ->
                                val result = chain.proceed()
                                try {
                                    val decoyId = decoyUserId()
                                    if (shouldFilterDecoyUserInSettings(decoyId)) {
                                        val activity = chain.getThisObject() as? android.app.Activity
                                        activity?.window?.decorView?.post {
                                            val res = activity.resources
                                            for (idName in listOf("account_avatar", "avatar_icon", "user_avatar")) {
                                                val id = res.getIdentifier(idName, "id", "com.android.settings")
                                                if (id != 0) {
                                                    activity.findViewById<android.view.View>(id)?.visibility = android.view.View.GONE
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w("NileHook", "Failed to hide homepage avatar", e)
                                }
                                result
                            }
                            Log.i("NileHook", "Hooked SettingsHomepageActivity.${m.name}")
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w("NileHook", "SettingsHomepageActivity hook skipped: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("NileHook", "hookSettingsApp failed", e)
        }
    }

    private fun hookInProcessUserManager(cl: ClassLoader) {
        try {
            val umClass = cl.loadClass("android.os.UserManager")
            val listMethods = setOf("getUsers", "getAliveUsers", "getProfiles", "getUserProfiles", "getUserHandles")
            umClass.declaredMethods.filter { it.name in listMethods }.forEach { method ->
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val decoyId = decoyUserId()
                    val filter = shouldFilterDecoyUser(decoyId)
                    if (!filter || result == null) return@intercept result

                    if (result is List<*>) {
                        val filtered = result.filter { item ->
                            val id = getUserIdFromObject(item)
                            id == null || id != decoyId
                        }
                        return@intercept filtered
                    }
                    result
                }
            }

            val disableMethods = setOf("isUserSwitcherEnabled", "supportsMultipleUsers", "canAddMoreUsers", "isMultipleUsersSupported")
            umClass.declaredMethods.filter { it.name in disableMethods }.forEach { method ->
                hook(method).intercept { chain ->
                    val decoyId = decoyUserId()
                    if (shouldFilterSwitcherInProcess(decoyId)) {
                        return@intercept false
                    }
                    chain.proceed()
                }
            }

            umClass.declaredMethods.filter { it.name == "getMaxSupportedUsers" }.forEach { method ->
                hook(method).intercept { chain ->
                    val decoyId = decoyUserId()
                    if (shouldFilterSwitcherInProcess(decoyId)) {
                        return@intercept 1
                    }
                    chain.proceed()
                }
            }

            Log.i("NileHook", "In-process UserManager hooked successfully")
        } catch (e: Throwable) {
            Log.w("NileHook", "Failed to hook in-process UserManager: ${e.message}")
        }
    }

    // --- Decoy on the real lock screen -----------------------------------------------------
    //
    // Hooks LockPatternUtils.checkCredential — SystemUI's own client-side call, made BEFORE the
    // credential ever reaches system_server over Binder. Same target as
    // github.com/leohearts/AlternativeUnlockXposed. An earlier version of this hook caused a real
    // wrong-PIN unlock on a physical device (the bogus-credential substitution below likely threw
    // and fell back to proceeding with the ORIGINAL unmodified credential). Since then the trigger
    // was rearchitected to run entirely via a direct root shell from this hook (see
    // triggerDecoyHide/triggerOneTimeUnlock below) instead of round-tripping through Nile's own
    // app process, and re-verified end-to-end on-device across both decoy modes. Still: this
    // substitutes a live authentication credential, so treat any change here as security-critical
    // and re-test both modes on a device you can afford to get locked out of before relying on it.
    //
    // fake_wrong_pin: when the typed credential matches a configured decoy code, trigger the hide,
    // then SUBSTITUTE the argument with a fixed bogus credential of the same type before calling
    // chain.proceed(...) — the real check then runs and genuinely rejects it, so the keyguard shows
    // its own authentic "wrong" state. Anything that isn't a decoy code proceeds untouched.

    /** mode, decoy codes, and the configured one_time_unlock use-limit (0 = unlimited). */
    private fun decoyLockConfig(): Triple<String, Set<String>, Int>? = try {
        val file = java.io.File("/data/system/thenile_config.json")
        if (!file.exists()) {
            Log.w("NileHook", "decoyLockConfig: file does not exist")
            null
        } else {
            val json = org.json.JSONObject(com.thenile.vault.root.ConfigCrypto.decrypt(file.readText()))
            val mode = json.optString("decoyLockScreenMode", "off")
            val arr = json.optJSONArray("decoyCodes")
            val codes = mutableSetOf<String>()
            if (arr != null) for (i in 0 until arr.length()) codes.add(arr.getString(i))
            val limit = json.optInt("decoyUnlockLimit", 1)
            Triple(mode, codes, limit)
        }
    } catch (e: Exception) {
        Log.e("NileHook", "decoyLockConfig failed", e)
        null
    }

    data class VaultEntry(
        val id: String,
        val name: String,
        val actionType: String, // "switch_user" or "hide_inplace"
        val targetUserId: Int,
        val decoyPin: String,
        val decoyDialerCode: String,
        val decoyCalculatorExpression: String,
        val packages: List<String>,
        val directories: List<String>,
        val dummies: List<Pair<String, String>>,
        val files: List<String>,
        val isActive: Boolean
    )

    private fun allVaultsFromConfig(): List<VaultEntry> = try {
        val json = readConfigJsonObject() ?: org.json.JSONObject(com.thenile.vault.root.ConfigCrypto.decrypt(java.io.File("/data/system/thenile_config.json").readText()))
        val arr = json.optJSONArray("vaults") ?: return emptyList()
        val list = mutableListOf<VaultEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val pkgs = mutableListOf<String>()
            obj.optJSONArray("packages")?.let { a -> for (j in 0 until a.length()) pkgs.add(a.getString(j)) }
            val dirs = mutableListOf<String>()
            obj.optJSONArray("directories")?.let { a -> for (j in 0 until a.length()) dirs.add(a.getString(j)) }
            val files = mutableListOf<String>()
            obj.optJSONArray("files")?.let { a -> for (j in 0 until a.length()) files.add(a.getString(j)) }
            val dummies = mutableListOf<Pair<String, String>>()
            obj.optJSONArray("dummyDirectories")?.let { a ->
                for (j in 0 until a.length()) {
                    val d = a.getJSONObject(j)
                    dummies.add(d.getString("target") to d.getString("dummy"))
                }
            }
            list.add(
                VaultEntry(
                    id = obj.optString("id"),
                    name = obj.optString("name", "Vault"),
                    actionType = obj.optString("actionType", if (obj.optInt("targetUserId", 0) > 0) "switch_user" else "hide_inplace"),
                    targetUserId = obj.optInt("targetUserId", 10),
                    decoyPin = obj.optString("decoyPin", ""),
                    decoyDialerCode = obj.optString("decoyDialerCode", ""),
                    decoyCalculatorExpression = obj.optString("decoyCalculatorExpression", ""),
                    packages = pkgs,
                    directories = dirs,
                    dummies = dummies,
                    files = files,
                    isActive = obj.optBoolean("isActive", true)
                )
            )
        }
        list
    } catch (e: Exception) {
        Log.e("NileHook", "allVaultsFromConfig failed", e)
        emptyList()
    }

    private fun findVaultForPin(pin: String): VaultEntry? {
        if (pin.isBlank()) return null
        return allVaultsFromConfig().firstOrNull { it.isActive && it.decoyPin == pin }
    }

    private fun findVaultForCalculator(expr: String): VaultEntry? {
        if (expr.isBlank()) return null
        val norm = normalizeCalcText(expr).removeSuffix("=").trim()
        return allVaultsFromConfig().firstOrNull {
            it.isActive && (normalizeCalcText(it.decoyCalculatorExpression).removeSuffix("=").trim() == norm ||
                it.decoyPin == norm)
        }
    }

    data class DecoyHidePayload(
        val packages: List<String>,
        val directories: List<String>,
        val dummies: List<Pair<String, String>>,
        val files: List<String>
    )

    /** packages / directories / (target,dummy) pairs / files to hide for one decoy code, read from the
     *  same config file SettingsManager.syncToSystem() already writes on every settings change. */
    private fun decoyHideDataFor(code: String): DecoyHidePayload? = try {
        val prof = findVaultForPin(code)
        if (prof != null) {
            DecoyHidePayload(prof.packages, prof.directories, prof.dummies, prof.files)
        } else {
            val json = org.json.JSONObject(com.thenile.vault.root.ConfigCrypto.decrypt(java.io.File("/data/system/thenile_config.json").readText()))
            val entries = json.optJSONArray("decoyHideData") ?: return null
            var found: org.json.JSONObject? = null
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                if (e.optString("code") == code) { found = e; break }
            }
            found ?: return null
            val pkgs = mutableListOf<String>()
            found.optJSONArray("packages")?.let { a -> for (i in 0 until a.length()) pkgs.add(a.getString(i)) }
            val dirs = mutableListOf<String>()
            found.optJSONArray("directories")?.let { a -> for (i in 0 until a.length()) dirs.add(a.getString(i)) }
            val files = mutableListOf<String>()
            found.optJSONArray("files")?.let { a -> for (i in 0 until a.length()) files.add(a.getString(i)) }
            val dummies = mutableListOf<Pair<String, String>>()
            found.optJSONArray("dummyDirectories")?.let { a ->
                for (i in 0 until a.length()) {
                    val d = a.getJSONObject(i)
                    dummies.add(d.getString("target") to d.getString("dummy"))
                }
            }
            DecoyHidePayload(pkgs, dirs, dummies, files)
        }
    } catch (e: Exception) {
        Log.e("NileHook", "decoyHideDataFor failed", e)
        null
    }

    /** Single-quote a value for safe embedding in the generated shell script. */
    private fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Runs the same hide operation StorageMountManager.mountDecoyDirectory + TraceCleaner do, as a
     *  single root shell script executed directly via su — bypassing Nile's own app process
     *  entirely. Waking Nile via broadcast (the previous approach) turned out to be unreliable: even
     *  with su + FLAG_RECEIVER_FOREGROUND + a directBootAware + goAsync() receiver, Android's process
     *  freezer could still interrupt Nile's background thread mid-mount when the device is locked
     *  (confirmed: dumpsys showed cached=true/isFrozen=true despite goAsync(), and the mount never
     *  completed). Plain root shell commands here have no app-process lifecycle to be frozen out of. */
    private fun oneTimeUnlockUsedCount(): Int = try {
        val f = java.io.File("/data/system/thenile_decoy_state.json")
        if (f.exists()) org.json.JSONObject(f.readText()).optInt("usedCount", 0) else 0
    } catch (e: Exception) {
        0
    }

    /** limit == 0 means unlimited (SettingsManager.decoyUnlockLimit's sentinel). */
    private fun hasOneTimeUnlockUsesLeft(limit: Int): Boolean = limit == 0 || oneTimeUnlockUsedCount() < limit

    /** Grants the actual one-time unlock. Deliberately its own function with zero call graph
     *  overlap with hookLockCredential's substitution logic below — it never constructs, reads,
     *  or compares a credential. TrustAgentService (the originally-researched "sanctioned" route)
     *  turned out to require the platform signing key (@SystemApi, "Trust agents may only be
     *  provided by the platform" — confirmed against AOSP master source), so this instead
     *  reflectively invokes KeyguardUpdateMonitor.onFingerprintAuthenticated(userId, true) from
     *  inside SystemUI's own process (where this hook already runs) — the same call a genuine
     *  fingerprint match produces. That method fails closed under Android's own StrongAuthTracker
     *  policy (e.g. it cannot succeed before first unlock after reboot), so this can't punch
     *  through a state where even a real biometric would be refused. Unsupported internal API:
     *  SystemUI's keyguard/biometric code is under active rewrite, so class/method names here may
     *  need updating on future Android versions — same maintenance burden as checkCredential above. */
    private fun triggerOneTimeUnlock(cl: ClassLoader) {
        try {
            val context = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
            if (context == null) {
                Log.e("NileHook", "one_time_unlock: no Context available, cannot unlock")
                return
            }
            val userId = try {
                Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as? Int ?: 0
            } catch (e: Exception) {
                0
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    // Must resolve against SystemUI's own classloader (cl) — the module's default
                    // Class.forName resolves against Nile's own dex and can't see this class at all.
                    val kum = cl.loadClass("com.android.keyguard.KeyguardUpdateMonitor")
                    // No public getInstance() in this build (SystemUI's keyguard code has moved to
                    // Dagger injection) — go through SystemUI's own service-locator instead:
                    // Dependency.sDependency.getDependencyInner(KeyguardUpdateMonitor.class), the
                    // same path Dependency.get(Class) itself uses upstream.
                    val depClass = cl.loadClass("com.android.systemui.Dependency")
                    val sDependencyField = depClass.getDeclaredField("sDependency")
                    sDependencyField.isAccessible = true
                    val depInstance = sDependencyField.get(null)
                    val getDependencyInner = depClass.getDeclaredMethod("getDependencyInner", Any::class.java)
                    getDependencyInner.isAccessible = true
                    val instance = getDependencyInner.invoke(depInstance, kum)
                    val method = kum.getMethod("onFingerprintAuthenticated", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    method.isAccessible = true
                    method.invoke(instance, userId, true)
                    Log.i("NileHook", "one_time_unlock: onFingerprintAuthenticated invoked for user $userId")
                } catch (e: Exception) {
                    Log.e("NileHook", "one_time_unlock: KeyguardUpdateMonitor invocation failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e("NileHook", "one_time_unlock: failed to obtain Context/userId", e)
        }
    }

    private fun triggerDecoyHide(code: String, recordOneTimeUnlockUse: Boolean = false) {
        try {
            val payload = decoyHideDataFor(code) ?: DecoyHidePayload(emptyList(), emptyList(), emptyList(), emptyList())
            val (packages, directories, dummies, files) = payload
            val sb = StringBuilder()
            sb.append("setprop ").append(com.thenile.vault.Config.STATE_PROP).append(" DECOY\n")
            sb.append("mkdir -p /data/system/dummy_dir\n")
            sb.append("USERS=\$(pm list users | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); [ -z \"\$USERS\" ] && USERS=0\n")
            for (pkg in packages) {
                val p = sq(pkg)
                sb.append("pm hide ").append(p).append("\n")
                sb.append("for U in \$USERS; do D=/data/user/\$U/").append(pkg).append("; [ -e \"\$D\" ] && nsenter -t 1 -m -- mount --bind /data/system/dummy_dir \"\$D\"; done\n")
                sb.append("am force-stop ").append(p).append("\n")
            }
            for (dir in directories) {
                val d = sq(dir)
                sb.append("mkdir -p ").append(d).append("\n")
                sb.append("nsenter -t 1 -m -- mount --bind /data/system/dummy_dir ").append(d).append("\n")
            }
            for (file in files) {
                val f = sq(file)
                val media = sq(file.replace("/sdcard/", "/data/media/0/").replace("/storage/emulated/0/", "/data/media/0/"))
                sb.append("nsenter -t 1 -m -- umount -l ").append(f).append("\n")
                sb.append("rm -f ").append(media).append(" ").append(f).append(" 2>/dev/null || true\n")
            }
            for ((target, dummy) in dummies) {
                val t = sq(target); val dm = sq(dummy)
                sb.append("mkdir -p ").append(t).append(" ").append(dm).append("\n")
                sb.append("nsenter -t 1 -m -- mount --bind ").append(dm).append(" ").append(t).append("\n")
            }
            // Safe snapshot & image cache cleanup (safe for system_server)
            sb.append("rm -rf /data/system_ce/0/snapshots/* /data/system_ce/0/recent_images/* /data/system/recent_images/* 2>/dev/null || true\n")

            val mediaTargets = (directories + files).distinct()
            if (mediaTargets.isNotEmpty()) {
                val mediaDbs = listOf(
                    "/data/data/com.android.providers.media.module/databases/external.db",
                    "/data/data/com.android.providers.media/databases/external.db",
                    "/data/user/0/com.android.providers.media.module/databases/external.db",
                    "/data/user/0/com.android.providers.media/databases/external.db"
                )
                val sqls = mutableListOf<String>()
                for (t in mediaTargets) {
                    val esc = t.replace("'", "''")
                    val mPath = esc.replace("/sdcard/", "/data/media/0/").replace("/storage/emulated/0/", "/data/media/0/")
                    sqls.add("DELETE FROM files WHERE _data LIKE '%$esc%' OR _data LIKE '%$mPath%';")
                    sqls.add("DELETE FROM images WHERE _data LIKE '%$esc%' OR _data LIKE '%$mPath%';")
                    sqls.add("DELETE FROM video WHERE _data LIKE '%$esc%' OR _data LIKE '%$mPath%';")
                    sqls.add("DELETE FROM audio WHERE _data LIKE '%$esc%' OR _data LIKE '%$mPath%';")
                }
                sqls.add("DELETE FROM thumbnails WHERE image_id NOT IN (SELECT _id FROM images);")
                val combined = sqls.joinToString(" ")
                for (db in mediaDbs) {
                    sb.append("[ -f '").append(db).append("' ] && sqlite3 '").append(db).append("' \"").append(combined).append("\" 2>/dev/null || true\n")
                }
            }

            sb.append("rm -rf /data/media/0/.thumbnails/* /data/media/0/DCIM/.thumbnails/* /sdcard/.thumbnails/* /sdcard/DCIM/.thumbnails/*\n")
            sb.append("rm -rf /data/data/com.android.providers.media.module/cache/* /data/data/com.android.providers.media/cache/*\n")
            sb.append("rm -rf /data/data/com.google.android.apps.photos/cache/*\n")
            sb.append("logcat -c\n")
            sb.append("sync; echo 3 > /proc/sys/vm/drop_caches\n")
            // Best-effort: keep Nile's own persisted state (and thus its Admin UI) in sync too, in
            // case its process happens to be running/resumes later — not required for the hide itself.
            sb.append("echo '<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map><string name=\"state\">DECOY</string></map>' > /data/data/com.thenile.vault/shared_prefs/vault_state.xml 2>/dev/null || true\n")

            if (recordOneTimeUnlockUse) {
                // Write the incremented count (computed in Kotlin, before the shell script runs)
                // to the same system_data_file-labeled location SettingsManager.decoyUnlockUsedCount
                // reads. A literal value here is simpler and just as safe as shell-side arithmetic
                // on a JSON field, since nothing else concurrently increments this.
                val newCount = oneTimeUnlockUsedCount() + 1
                val tmp = "/data/local/tmp/thenile_decoy_state.tmp"
                val path = "/data/system/thenile_decoy_state.json"
                sb.append("echo '{\"usedCount\":").append(newCount).append("}' > ").append(tmp).append("\n")
                sb.append("mv ").append(tmp).append(" ").append(path).append("\n")
                sb.append("chmod 644 ").append(path).append("\n")
                sb.append("chcon u:object_r:system_data_file:s0 ").append(path).append("\n")
            }

            Runtime.getRuntime().exec(arrayOf("su", "-c", sb.toString()))
        } catch (e: Exception) {
            Log.e("NileHook", "failed to run decoy hide", e)
        }
    }

    @Volatile
    private var lastSwitchTime = 0L

    /** Covers Android's own "Switching to <user>…" dialog. There's currently no working path to
     *  suppress it at the source — that lives in system_server's UserController/UserSwitchingDialog,
     *  and onSystemServerStarting never fires for third-party modules on this Vector build (the
     *  onPackageLoaded("android") fallback fires unpredictably in whichever other process happens
     *  to load framework classes, never system_server itself — confirmed on-device: hookSystemServer
     *  only ever ran inside e.g. com.android.systemui or a random third-party app, never PID of the
     *  real system_server). So instead of hiding it, launch our own full-screen activity that reads
     *  as the same ordinary "<model> is starting…" transition on top of it. */
    private fun showSwitchCoverScreen() {
        try {
            val context = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context ?: return
            val intent = android.content.Intent().apply {
                setClassName("com.thenile.vault", "com.thenile.vault.ui.PromptActivity")
                putExtra("COVER_ONLY", true)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w("NileHook", "showSwitchCoverScreen failed", e)
        }
    }

    private fun triggerSwitchUser(userId: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSwitchTime < 1500) {
            Log.d("NileHook", "triggerSwitchUser($userId) throttled")
            return
        }
        lastSwitchTime = now
        showSwitchCoverScreen()
        Thread {
            try {
                Log.i("NileHook", "triggerSwitchUser: switching to user $userId via ActivityManager.getService()")
                val amClass = Class.forName("android.app.ActivityManager")
                val getService = amClass.getMethod("getService")
                val iam = getService.invoke(null)
                val switchMethod = iam.javaClass.methods.firstOrNull { it.name == "switchUser" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType }
                if (switchMethod != null) {
                    val res = switchMethod.invoke(iam, userId)
                    Log.i("NileHook", "triggerSwitchUser: switchUser($userId) returned $res")
                    return@Thread
                }
            } catch (e: Exception) {
                Log.w("NileHook", "triggerSwitchUser reflection failed", e)
            }
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "am switch-user $userId"))
            } catch (e: Exception) {
                Log.e("NileHook", "triggerSwitchUser su fallback failed", e)
            }
        }.start()
    }

    /** The bouncer shows its normal "Wrong PIN. Try again." error after our deliberate bogus-
     *  credential substitution above (see hookLockCredential) makes the real check genuinely fail
     *  — a real intruder's wrong PIN should still show it, but a decoy code that just triggered a
     *  switch shouldn't. Confirmed via live tracing (KeyguardAbsKeyInputViewController.showMessage,
     *  hooked here previously, never actually fires) that the real chain is
     *  onPasswordChecked -> KeyguardMessageAreaController.setMessage(int resId) -> cascades through
     *  several KeyguardMessageAreaController.setMessage overloads -> KeyguardMessageArea.setMessage
     *  (CharSequence, boolean), which is the actual View-level sink that renders the text — the
     *  fully-resolved final text ("Wrong PIN. Try again.") only appears at THIS call per the trace,
     *  not at the controller's 1-arg overload (which only ever saw the shorter "Wrong PIN"). Hooking
     *  here, right before rendering, is robust to which upstream overload cascade produced it. Runs
     *  in the same SystemUI process as hookLockCredential (both installed from the same
     *  onPackageLoaded call site), so it can just read lastSwitchTime directly. */
    private fun hookWrongPinMessage(cl: ClassLoader) {
        try {
            val clazz = loadServiceClass(cl, "com.android.keyguard.KeyguardMessageArea")
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "setMessage" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == CharSequence::class.java
            } ?: return
            hook(method).intercept { chain ->
                val msg = chain.getArg(0) as? CharSequence
                val now = android.os.SystemClock.uptimeMillis()
                if (msg != null && msg.contains("Wrong", ignoreCase = true) && now - lastSwitchTime < 1500) {
                    Log.i("NileHook", "Suppressing wrong-PIN bouncer message after decoy switch trigger")
                    return@intercept null
                }
                chain.proceed()
            }
            Log.i("NileHook", "Hooked KeyguardMessageArea.setMessage(CharSequence, boolean)")
        } catch (e: Exception) {
            Log.w("NileHook", "Failed to hook wrong-PIN message: ${e.message}")
        }
    }

    private fun verifyCredentialForUser(instance: Any?, cred: Any?, targetUserId: Int): Boolean {
        if (instance == null || cred == null) return false
        return try {
            val lockSettings = if (instance.javaClass.name.contains("LockSettingsService")) {
                instance
            } else {
                val getLockSettings = instance.javaClass.methods.firstOrNull { it.name == "getLockSettings" }
                    ?: instance.javaClass.getDeclaredMethod("getLockSettings").also { it.isAccessible = true }
                getLockSettings.invoke(instance) ?: return false
            }
            val checkMethod = lockSettings.javaClass.methods.firstOrNull {
                (it.name == "checkCredential" || it.name == "verifyCredential" || it.name == "doVerifyCredential") &&
                    it.parameterTypes.size >= 2 &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: return false
            val args = arrayOfNulls<Any>(checkMethod.parameterTypes.size)
            args[0] = cred
            args[1] = targetUserId
            if (checkMethod.parameterTypes.size >= 3 && checkMethod.parameterTypes[2] == Int::class.javaPrimitiveType) {
                args[2] = 0 // flags
            }
            val response = checkMethod.invoke(lockSettings, *args) ?: run {
                Log.w("NileHook", "verifyCredentialForUser: invoke returned null")
                return false
            }
            val getResponseCode = try { response.javaClass.getMethod("getResponseCode") } catch (e: Exception) { null }
            val code = getResponseCode?.invoke(response) as? Int
            val isMatched = try { response.javaClass.getMethod("isMatched") } catch (e: Exception) { null }
            val matched = isMatched?.invoke(response) as? Boolean
            val getTimeout = try { response.javaClass.getMethod("getTimeout") } catch (e: Exception) { null }
            val timeout = getTimeout?.invoke(response) as? Int
            Log.i("NileHook", "verifyCredentialForUser response: $response code=$code matched=$matched timeout=$timeout")
            if (code != null && code == 0) return true
            if (matched == true) return true
            if (timeout != null && timeout == 0 && code == 0) return true
            false
        } catch (e: Exception) {
            Log.w("NileHook", "verifyCredentialForUser failed", e)
            false
        }
    }

    private fun makeSuccessResponse(cl: ClassLoader, returnType: Class<*>): Any? {
        if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
            return true
        }
        if (returnType.name.contains("VerifyCredentialResponse")) {
            return try {
                val respClass = cl.loadClass("com.android.internal.widget.VerifyCredentialResponse")
                respClass.getField("OK_RESPONSE").get(null)
            } catch (e: Exception) {
                try {
                    val respClass = cl.loadClass("com.android.internal.widget.VerifyCredentialResponse")
                    respClass.getDeclaredMethod("fromGateKeeperResponse", Any::class.java).invoke(null, null)
                } catch (e2: Exception) {
                    null
                }
            }
        }
        return null
    }

    private fun createBogusCredential(cred: Any): Any? {
        return try {
            val type = cred.javaClass.getMethod("getType").invoke(cred) as? Int
            val ctor = cred.javaClass.getDeclaredConstructor(Int::class.javaPrimitiveType, CharSequence::class.java)
            ctor.isAccessible = true
            ctor.newInstance(type, BOGUS_CREDENTIAL)
        } catch (e: Exception) {
            null
        }
    }

    private fun hookLockCredential(cl: ClassLoader) {
        val targetClassNames = listOf(
            "com.android.internal.widget.LockPatternUtils"
        )
        for (className in targetClassNames) {
            try {
                val clazz = try {
                    loadServiceClass(cl, className)
                } catch (e: Exception) {
                    continue
                }
                // ponytail: temporary diagnostic — dump every credential-ish overload this build
                // actually declares, unfiltered, so a runtime mismatch between what we hook and
                // what the bouncer actually calls is visible in logcat instead of just "no log
                // ever fires". Remove once the real overload is confirmed and the filter is fixed.
                try {
                    for (m in clazz.declaredMethods) {
                        if (m.name.contains("redential", ignoreCase = true)) {
                            Log.i("NileHook", "DIAG $className.${m.name}(${m.parameterTypes.joinToString(", ") { it.name }}): ${m.returnType.name}")
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("NileHook", "DIAG dump failed for $className", e)
                }
                val methods = clazz.declaredMethods.filter {
                    it.name in setOf("checkCredential", "verifyCredential", "doVerifyCredential") &&
                        it.parameterTypes.isNotEmpty() &&
                        it.parameterTypes[0].name.contains("LockscreenCredential")
                }
                if (methods.isEmpty()) continue
                for (method in methods) {
                    hook(method).intercept { chain ->
                        try {
                            Log.i("NileHook", "${className}.${method.name} intercepted, args=${chain.args.size}")
                            val (mode, codes, unlockLimit) = decoyLockConfig() ?: return@intercept chain.proceed()
                            Log.i("NileHook", "config: mode=$mode codes=$codes unlockLimit=$unlockLimit")
                            if (mode == "off") return@intercept chain.proceed()

                            val cred = chain.getArg(0) ?: return@intercept chain.proceed()
                            val bytes = try {
                                cred.javaClass.getMethod("getCredential").invoke(cred) as? ByteArray
                            } catch (e: Exception) {
                                Log.e("NileHook", "getCredential reflection failed", e)
                                null
                            }
                            val typed = bytes?.let { String(it, Charsets.US_ASCII) }
                            Log.i("NileHook", "typed credential='$typed' len=${bytes?.size} codes=$codes matched=${typed in codes}")
                            if (typed.isNullOrEmpty()) return@intercept chain.proceed()

                            val currentUserId = chain.args.firstNotNullOfOrNull { it as? Int } ?: try {
                                Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as? Int ?: 0
                            } catch (e: Exception) {
                                0
                            }
                            Log.i("NileHook", "intercepted credential verification for userId=$currentUserId (typed len=${bytes?.size})")

                            if (currentUserId > 0) {
                                // In Decoy vault: check if the typed PIN matches User 0 (Master PIN)
                                Log.i("NileHook", "Checking if typed PIN on Decoy vault $currentUserId matches User 0 Master PIN...")
                                if (verifyCredentialForUser(chain.getThisObject(), cred, 0)) {
                                    Log.i("NileHook", "Master PIN entered on Decoy lockscreen — switching back to User 0")
                                    triggerSwitchUser(0)
                                    val bogus = createBogusCredential(cred)
                                    if (bogus != null) {
                                        val args = chain.args.toMutableList()
                                        args[0] = bogus
                                        return@intercept chain.proceed(args.toTypedArray())
                                    }
                                    return@intercept chain.proceed()
                                }
                                // Check if typed PIN matches another decoy vault
                                val targetProf = findVaultForPin(typed)
                                if (targetProf != null) {
                                    Log.i("NileHook", "Decoy PIN for vault '${targetProf.name}' entered on secondary user $currentUserId")
                                    if (targetProf.actionType == "switch_user" && targetProf.targetUserId != currentUserId) {
                                        triggerSwitchUser(targetProf.targetUserId)
                                        val bogus = createBogusCredential(cred)
                                        if (bogus != null) {
                                            val args = chain.args.toMutableList()
                                            args[0] = bogus
                                            return@intercept chain.proceed(args.toTypedArray())
                                        }
                                        return@intercept chain.proceed()
                                    } else if (targetProf.actionType == "hide_inplace") {
                                        triggerSwitchUser(0)
                                        triggerDecoyHide(typed)
                                        val bogus = createBogusCredential(cred)
                                        if (bogus != null) {
                                            val args = chain.args.toMutableList()
                                            args[0] = bogus
                                            return@intercept chain.proceed(args.toTypedArray())
                                        }
                                        return@intercept chain.proceed()
                                    }
                                }
                                // Otherwise let Decoy vault's normal credential verification proceed
                                return@intercept chain.proceed()
                            }

                            // Main user (User 0) PIN check
                            // Check if the typed PIN is actually another vault's own real
                            // lockscreen credential (not just an arbitrary decoyPin trigger code)
                            // — lets a vault with its own PIN set be switched to directly by
                            // typing that PIN on Owner's lockscreen, symmetric to the Decoy ->
                            // Owner master-PIN check above.
                            for (prof in allVaultsFromConfig()) {
                                if (!prof.isActive || prof.actionType != "switch_user" || prof.targetUserId == currentUserId) continue
                                if (verifyCredentialForUser(chain.getThisObject(), cred, prof.targetUserId)) {
                                    Log.i("NileHook", "Vault '${prof.name}' own credential entered on User 0 — switching to User ${prof.targetUserId}")
                                    triggerSwitchUser(prof.targetUserId)
                                    val bogus = createBogusCredential(cred)
                                    if (bogus != null) {
                                        val args = chain.args.toMutableList()
                                        args[0] = bogus
                                        return@intercept chain.proceed(args.toTypedArray())
                                    }
                                    return@intercept chain.proceed()
                                }
                            }

                            val matchedVault = findVaultForPin(typed)
                            if (matchedVault != null) {
                                Log.i("NileHook", "Vault '${matchedVault.name}' (action=${matchedVault.actionType}) MATCHED on User 0 with PIN '$typed'")
                                if (matchedVault.actionType == "switch_user") {
                                    triggerSwitchUser(matchedVault.targetUserId)
                                    val bogus = createBogusCredential(cred)
                                    if (bogus != null) {
                                        val args = chain.args.toMutableList()
                                        args[0] = bogus
                                        return@intercept chain.proceed(args.toTypedArray())
                                    }
                                    return@intercept chain.proceed()
                                } else {
                                    // in-place hide
                                    triggerDecoyHide(typed, recordOneTimeUnlockUse = true)
                                    triggerOneTimeUnlock(cl)
                                    val bogus = createBogusCredential(cred)
                                    if (bogus != null) {
                                        val args = chain.args.toMutableList()
                                        args[0] = bogus
                                        return@intercept chain.proceed(args.toTypedArray())
                                    }
                                    return@intercept chain.proceed()
                                }
                            }

                            if (codes.isEmpty()) return@intercept chain.proceed()
                            if (typed !in codes) return@intercept chain.proceed()

                            when (mode) {
                                "switch_user" -> {
                                    val decoyId = decoyUserId()
                                    Log.i("NileHook", "switch_user code MATCHED on User 0 — switching to User $decoyId")
                                    if (decoyId >= 0) {
                                        triggerSwitchUser(decoyId)
                                    }
                                    val bogus = createBogusCredential(cred)
                                    if (bogus != null) {
                                        val args = chain.args.toMutableList()
                                        args[0] = bogus
                                        return@intercept chain.proceed(args.toTypedArray())
                                    }
                                    return@intercept chain.proceed()
                                }
                                "one_time_unlock" -> {
                                    if (!hasOneTimeUnlockUsesLeft(unlockLimit)) {
                                        Log.i("NileHook", "one_time_unlock: no uses left (limit=$unlockLimit), treating as normal wrong PIN")
                                        return@intercept chain.proceed()
                                    }
                                    Log.i("NileHook", "one_time_unlock code MATCHED — hiding + triggering unlock (limit=$unlockLimit)")
                                    triggerDecoyHide(typed, recordOneTimeUnlockUse = true)
                                    triggerOneTimeUnlock(cl)
                                    val bogus = createBogusCredential(cred)
                                    if (bogus != null) {
                                        val args = chain.args.toMutableList()
                                        args[0] = bogus
                                        return@intercept chain.proceed(args.toTypedArray())
                                    }
                                    return@intercept chain.proceed()
                                }
                                "fake_wrong_pin" -> {
                                    Log.i("NileHook", "decoy code MATCHED — hiding, substituting bogus credential")
                                    triggerDecoyHide(typed)
                                    val bogus = createBogusCredential(cred)
                                    if (bogus != null) {
                                        val args = chain.args.toMutableList()
                                        args[0] = bogus
                                        return@intercept chain.proceed(args.toTypedArray())
                                    }
                                    return@intercept chain.proceed()
                                }
                                else -> return@intercept chain.proceed()
                            }
                        } catch (t: Throwable) {
                            Log.e("NileHook", "Exception in lock credential interceptor", t)
                            return@intercept chain.proceed()
                        }
                    }
                    Log.i("NileHook", "$className credential methods hooked (${methods.size} overload(s))")
                }
            } catch (e: Exception) {
                Log.e("NileHook", "lock credential hook failed for $className", e)
            }
        }
    }

    // --- Calculator disguise trigger --------------------------------------------------------
    //
    // Hooks the REAL system Calculator app's android.widget.TextView.setText — a public,
    // stable Android framework API — instead of the calculator app's own private internal
    // classes. That means one hook works across different calculator apps (Google Calculator,
    // AOSP ExactCalculator, and most OEM ones) with no per-app reverse engineering and far less
    // version fragility than e.g. the SystemUI keyguard hooks above. When the currently
    // displayed text exactly matches the configured trigger expression (after normalizing
    // whitespace and Unicode math glyphs), launches Nile's Admin activity.

    // Keyed by the TextView instance (not a single shared flag) — a calculator's formula view
    // and its live result-preview view both call setText independently, so a global guard could
    // get reset by the OTHER view's unrelated text and let the same view's match re-fire.
    private val lastCalculatorMatch = java.util.WeakHashMap<Any, String>()

    /** Strips whitespace and maps Unicode math glyphs (−×÷) to ASCII so the match doesn't care
     *  which symbols the calculator's own keypad/display happens to use. */
    private fun normalizeCalcText(s: CharSequence?): String {
        if (s == null) return ""
        return s.toString()
            .replace('−', '-')
            .replace('×', '*')
            .replace('✕', '*')
            .replace('÷', '/')
            .filterNot { it.isWhitespace() }
    }

    private fun readConfigJsonObject(): org.json.JSONObject? = try {
        val f = java.io.File("/data/system/thenile_config.json")
        if (f.exists()) org.json.JSONObject(com.thenile.vault.root.ConfigCrypto.decrypt(f.readText())) else null
    } catch (e: Exception) {
        null
    }

    private fun allDecoyCodes(): Set<String> {
        val set = mutableSetOf("1234")
        try {
            val json = readConfigJsonObject() ?: return set
            val arr = json.optJSONArray("decoyCodes")
            if (arr != null) for (i in 0 until arr.length()) set.add(arr.getString(i))
            val codeDecoy = json.optString("codeDecoy", "")
            if (codeDecoy.isNotBlank()) set.add(codeDecoy)
        } catch (e: Exception) {}
        return set
    }

    private fun allMasterCodes(): Set<String> {
        val set = mutableSetOf("8888", "9876", "1111", "3333")
        try {
            val json = readConfigJsonObject() ?: return set
            val arr = json.optJSONArray("masterCodes")
            if (arr != null) for (i in 0 until arr.length()) set.add(arr.getString(i))
            val codeUnlock = json.optString("codeUnlock", "")
            if (codeUnlock.isNotBlank()) set.add(codeUnlock)
            val codeAdmin = json.optString("codeAdmin", "")
            if (codeAdmin.isNotBlank()) set.add(codeAdmin)
            val codeLock = json.optString("codeLock", "")
            if (codeLock.isNotBlank()) set.add(codeLock)
        } catch (e: Exception) {}
        return set
    }

    private fun calculatorTriggerExpression(): String = try {
        val json = readConfigJsonObject()
        normalizeCalcText(json?.optString("calculatorTriggerExpression", ""))
    } catch (e: Exception) {
        ""
    }

    private fun hookCalculatorTrigger(cl: ClassLoader) {
        try {
            val textView = cl.loadClass("android.widget.TextView")
            val methods = textView.declaredMethods.filter {
                it.name == "setText" && it.parameterTypes.isNotEmpty() &&
                    CharSequence::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            if (methods.isEmpty()) {
                Log.w("NileHook", "TextView.setText not found for calculator hook")
                return
            }
            for (method in methods) {
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val rawTyped = normalizeCalcText(chain.getArg(0) as? CharSequence)
                        val typed = rawTyped.trim()
                        val view = chain.getThisObject()
                        if (typed.isNotEmpty()) {
                            if (view != null && lastCalculatorMatch[view] != typed) {
                                if (handleCalculatorInput(typed)) {
                                    lastCalculatorMatch[view] = typed
                                }
                            }
                        } else if (view != null) {
                            lastCalculatorMatch.remove(view)
                        }
                    } catch (e: Exception) {
                        Log.e("NileHook", "calculator trigger check failed", e)
                    }
                    result
                }
            }
            Log.i("NileHook", "Calculator TextView.setText hooked (${methods.size} overload(s))")
        } catch (e: Exception) {
            Log.e("NileHook", "calculator hook failed", e)
        }
    }

    private fun handleCalculatorInput(typed: String): Boolean {
        val stripped = typed.removeSuffix("=").trim()
        val decoyCodes = allDecoyCodes()
        val masterCodes = allMasterCodes()
        val customTrigger = calculatorTriggerExpression()
        val decoyId = decoyUserId()
        val currentUserId = try {
            Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as? Int ?: 0
        } catch (e: Exception) {
            0
        }

        val matchedVault = findVaultForCalculator(typed) ?: findVaultForCalculator(stripped)

        val isDecoyMatch = (typed in decoyCodes || stripped in decoyCodes ||
            (customTrigger.isNotEmpty() && (typed == customTrigger || stripped == customTrigger.removeSuffix("="))))

        val isMasterMatch = (typed in masterCodes || stripped in masterCodes ||
            typed == "8888" || stripped == "8888")

        val isAdminMatch = (typed == "3333" || stripped == "3333" || typed == "9876" || stripped == "9876")

        if (currentUserId == 0) {
            if (matchedVault != null) {
                Log.i("NileHook", "Calculator: Vault '${matchedVault.name}' trigger MATCHED ($typed) on User 0 (action=${matchedVault.actionType})")
                if (matchedVault.actionType == "switch_user") {
                    triggerSwitchUser(matchedVault.targetUserId)
                } else {
                    triggerDecoyHide(matchedVault.decoyPin.ifEmpty { stripped })
                }
                return true
            }
            if (isDecoyMatch) {
                Log.i("NileHook", "Calculator: Decoy trigger MATCHED ($typed) on User 0 -> switching to User $decoyId")
                if (decoyId >= 0) {
                    triggerSwitchUser(decoyId)
                } else {
                    triggerDecoyHide(stripped)
                }
                return true
            }
            if (isAdminMatch) {
                Log.i("NileHook", "Calculator: Admin trigger MATCHED ($typed) on User 0 -> launching Admin")
                launchAdminFromCalculator()
                return true
            }
        } else {
            // In Decoy Vault (User 10 or any secondary vault)
            if (isMasterMatch) {
                Log.i("NileHook", "Calculator: Master trigger MATCHED ($typed) on Decoy User $currentUserId -> switching to User 0")
                triggerSwitchUser(0)
                return true
            }
            if (matchedVault != null) {
                Log.i("NileHook", "Calculator: Vault '${matchedVault.name}' trigger MATCHED ($typed) on Decoy User $currentUserId")
                if (matchedVault.actionType == "switch_user" && matchedVault.targetUserId != currentUserId) {
                    triggerSwitchUser(matchedVault.targetUserId)
                    return true
                } else if (matchedVault.actionType == "hide_inplace") {
                    triggerSwitchUser(0)
                    triggerDecoyHide(matchedVault.decoyPin.ifEmpty { stripped })
                    return true
                }
            }
        }
        return false
    }

    private fun launchAdminFromCalculator() {
        try {
            val currentUserId = try {
                Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as? Int ?: 0
            } catch (e: Exception) {
                0
            }
            if (currentUserId != 0) {
                triggerSwitchUser(0)
            }
            val context = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context ?: return
            val intent = android.content.Intent()
            intent.setClassName("com.thenile.vault", "com.thenile.vault.ui.AdminActivity")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("NileHook", "failed to launch Admin from calculator trigger", e)
        }
    }

    // --- Phone app / Dialer vault switching trigger ----------------------------------------
    private val lastDialerMatch = java.util.WeakHashMap<Any, String>()

    private fun extractDialDigits(s: CharSequence?): String {
        if (s == null) return ""
        var raw = s.toString().trim()
        if (raw.startsWith("*#*#") && raw.endsWith("#*#*") && raw.length > 8) {
            raw = raw.removePrefix("*#*#").removeSuffix("#*#*")
        } else if (raw.startsWith("*#") && raw.endsWith("#") && raw.length > 3) {
            raw = raw.removePrefix("*#").removeSuffix("#")
        } else if (raw.startsWith("*#")) {
            raw = raw.removePrefix("*#")
        }
        return raw.filter { it.isDigit() || it == '*' || it == '#' }
    }

    private fun handleDialedNumber(raw: CharSequence?): Boolean {
        val digits = extractDialDigits(raw)
        if (digits.isEmpty()) return false
        val decoyCodes = allDecoyCodes()
        val masterCodes = allMasterCodes()
        val decoyId = decoyUserId()
        val currentUserId = try {
            Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as? Int ?: 0
        } catch (e: Exception) {
            0
        }

        if (digits in decoyCodes || digits == "1234") {
            if (currentUserId == 0) {
                Log.i("NileHook", "Phone: Decoy dial code MATCHED ($digits) on User 0 -> switching to User $decoyId")
                if (decoyId >= 0) {
                    triggerSwitchUser(decoyId)
                } else {
                    triggerDecoyHide(digits)
                }
                return true
            }
        }

        if (digits in masterCodes || digits == "8888" || digits == "9876" || digits == "1111" || digits == "3333") {
            if (currentUserId != 0) {
                Log.i("NileHook", "Phone: Master dial code MATCHED ($digits) on Decoy User $currentUserId -> switching to User 0")
                triggerSwitchUser(0)
                return true
            } else if (digits == "3333" || digits == "9876") {
                Log.i("NileHook", "Phone: Admin dial code MATCHED ($digits) on User 0 -> launching Admin")
                launchAdminFromCalculator()
                return true
            }
        }
        return false
    }

    private fun hookDialerTrigger(cl: ClassLoader) {
        try {
            // 1. Hook TextView/EditText.setText in Dialer processes
            val textView = cl.loadClass("android.widget.TextView")
            val methods = textView.declaredMethods.filter {
                it.name == "setText" && it.parameterTypes.isNotEmpty() &&
                    CharSequence::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            for (method in methods) {
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val text = chain.getArg(0) as? CharSequence
                        val view = chain.getThisObject()
                        if (!text.isNullOrEmpty()) {
                            val raw = text.toString()
                            val digits = extractDialDigits(raw)
                            if (view != null && lastDialerMatch[view] != digits) {
                                if (digits.length >= 4 && (digits in allDecoyCodes() || digits in allMasterCodes() || digits == "1234" || digits == "8888")) {
                                    if (handleDialedNumber(raw)) {
                                        lastDialerMatch[view] = digits
                                        (view as? android.widget.TextView)?.post {
                                            try { (view as? android.widget.TextView)?.text = "" } catch (e: Exception) {}
                                        }
                                    }
                                }
                            }
                        } else if (view != null) {
                            lastDialerMatch.remove(view)
                        }
                    } catch (e: Exception) {
                        Log.e("NileHook", "dialer TextView check failed", e)
                    }
                    result
                }
            }

            // 2. Hook Intent call placement to intercept calls to 1234, 8888, *#1234#, etc.
            try {
                val contextWrapperClass = cl.loadClass("android.content.ContextWrapper")
                val startActMethods = contextWrapperClass.declaredMethods.filter {
                    it.name == "startActivity" && it.parameterTypes.isNotEmpty() &&
                        android.content.Intent::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                for (m in startActMethods) {
                    hook(m).intercept { chain ->
                        val intent = chain.getArg(0) as? android.content.Intent
                        if (intent != null && intent.action in setOf(
                                android.content.Intent.ACTION_CALL,
                                "android.intent.action.CALL_PRIVILEGED",
                                android.content.Intent.ACTION_DIAL
                            )) {
                            val data = intent.data
                            val number = data?.schemeSpecificPart
                            if (handleDialedNumber(number)) {
                                Log.i("NileHook", "Intercepted and consumed dial call intent: $number")
                                return@intercept null
                            }
                        }
                        chain.proceed()
                    }
                }
            } catch (e: Exception) {}

            try {
                val telecomManagerClass = cl.loadClass("android.telecom.TelecomManager")
                telecomManagerClass.declaredMethods.filter { it.name == "placeCall" }.forEach { m ->
                    hook(m).intercept { chain ->
                        val uri = chain.args.firstNotNullOfOrNull { it as? android.net.Uri }
                        val number = uri?.schemeSpecificPart
                        if (handleDialedNumber(number)) {
                            Log.i("NileHook", "TelecomManager.placeCall intercepted and consumed: $number")
                            return@intercept null
                        }
                        chain.proceed()
                    }
                }
            } catch (e: Exception) {}

            Log.i("NileHook", "Dialer hooks installed successfully")
        } catch (e: Exception) {
            Log.e("NileHook", "hookDialerTrigger failed", e)
        }
    }

    companion object {
        // ponytail: fixed sentinel, not random — the real check only needs it to NOT equal the
        // stored credential, and a fixed string is enough since it's never persisted or compared
        // to anything but the actual gatekeeper/synthetic-password hash.
        private const val BOGUS_CREDENTIAL = " nile_decoy_reject_ "

        private val CALCULATOR_PACKAGES = setOf(
            "com.android.calculator2",
            "com.google.android.calculator",
            "com.sec.android.app.popupcalculator",
            "com.miui.calculator",
            "com.simplemobiletools.calculator"
        )

        private val DIALER_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.simplemobiletools.dialer",
            "com.coloros.phoneno"
        )

        private val KNOWN_LAUNCHERS = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.bbk.launcher2",
            "com.oneplus.launcher",
            "com.teslacoilsw.launcher",
            "com.teslacoilsw.launcher.prime",
            "com.actionlauncher.playstore",
            "com.lawnchair.launcher",
            "app.lawnchair",
            "ch.deletescape.lawnchair.ci",
            "com.microsoft.launcher",
            "com.smartlauncher",
            "ginlemon.flowerfree",
            "ginlemon.flowerpro",
            "ninja.sesame.app.edge",
            "com.niagara.launcher",
            "bitpit.launcher"
        )

        private val XPOSED_AND_ROOT_MANAGERS = setOf(
            "org.lsposed.manager",
            "io.github.vector",
            "io.github.vector.manager",
            "com.solohsu.android.edxp.manager",
            "org.meowcat.edxposed.manager",
            "de.robv.android.xposed.installer",
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
            "me.weishu.kernelsu",
            "com.rifsxd.ksu",
            "com.sukisu.ultra",
            "bin.mt.plus"
        )

        private val SYSTEM_NETWORK_PACKAGES = setOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.carrierconfig",
            "com.google.android.carrier",
            "com.android.networkstack",
            "com.android.networkstack.process",
            "com.android.networkstack.tethering",
            "com.google.android.networkstack",
            "com.google.android.networkstack.tethering",
            "com.android.providers.telephony",
            "com.qualcomm.qti.telephonyservice",
            "com.qualcomm.qti.cnd",
            "com.qualcomm.qti.carrierconfigure",
            "com.qualcomm.qti.uim",
            "com.qualcomm.qcrilmsgtunnel",
            "com.google.android.hiddennetwork",
            "com.android.server.telephony",
            "com.android.voicemail",
            "com.google.android.apps.carrier.carrierwifi",
            "com.google.android.ims",
            "com.samsung.advp.imsservice",
            "com.sec.imsservice",
            "com.samsung.android.app.telephonyui",
            "com.samsung.android.incallui",
            "com.samsung.android.connectivity",
            "com.mediatek.ims",
            "com.mediatek.telephony",
            "com.mediatek.capctrl.service",
            "com.sh.smartvoicemail",
            "com.att.vvm",
            "com.vzw.vvm",
            "com.tmobile.vvm.application"
        )
    }

    private fun loadServiceClass(cl: ClassLoader, className: String): Class<*> {
        return try {
            cl.loadClass(className)
        } catch (e: ClassNotFoundException) {
            val servicesCl = dalvik.system.PathClassLoader("/system/framework/services.jar", cl)
            servicesCl.loadClass(className)
        }
    }

    private fun hookUserManager(cl: ClassLoader) {
        try {
            val umsClass = loadServiceClass(cl, "com.android.server.pm.UserManagerService")
            Log.i("NileHook", "Installing UserManagerService hooks on $umsClass")

            val userListMethods = setOf(
                "getUsers", "getUsersInternal", "getAliveUsers", "getProfiles", "getVisibleUsers",
                "getUserIds", "getUserIdsIncludingPreCreated", "getProfileIds"
            )

            umsClass.declaredMethods.filter { it.name in userListMethods }.forEach { method ->
                hook(method).intercept { chain ->
                    val callingUid = android.os.Binder.getCallingUid()
                    if (callingUid == 1001 || callingUid == 1073 || callingUid == 0) {
                        return@intercept chain.proceed()
                    }
                    val result = chain.proceed()
                    val decoyId = decoyUserId()
                    val filter = shouldFilterDecoyUser(decoyId)
                    Log.i("NileHook", "UMS.${method.name} intercepted, decoyId=$decoyId filter=$filter resultType=${result?.javaClass?.simpleName}")
                    if (!filter || result == null) return@intercept result

                    if (result is List<*>) {
                        val filtered = result.filter { item ->
                            val id = getUserIdFromObject(item)
                            id == null || id != decoyId
                        }
                        Log.i("NileHook", "UMS.${method.name}: filtered list size from ${result.size} to ${filtered.size}")
                        return@intercept filtered
                    } else if (result is IntArray) {
                        val filtered = result.filter { it != decoyId }.toIntArray()
                        Log.i("NileHook", "UMS.${method.name}: filtered int[] size from ${result.size} to ${filtered.size}")
                        return@intercept filtered
                    }
                    result
                }
            }

            val disableMethods = setOf("isUserSwitcherEnabled", "supportsMultipleUsers", "canAddMoreUsers", "canAddMoreManagedProfiles", "isMultipleUsersSupported")
            umsClass.declaredMethods.filter { it.name in disableMethods }.forEach { method ->
                hook(method).intercept { chain ->
                    val callingUid = android.os.Binder.getCallingUid()
                    if (callingUid == 1001 || callingUid == 1073 || callingUid == 0) {
                        return@intercept chain.proceed()
                    }
                    val decoyId = decoyUserId()
                    if (shouldFilterDecoyUser(decoyId)) {
                        Log.i("NileHook", "UMS.${method.name} intercepted -> returning false")
                        return@intercept false
                    }
                    chain.proceed()
                }
            }

            umsClass.declaredMethods.filter { it.name == "getUserSwitchability" }.forEach { method ->
                hook(method).intercept { chain ->
                    val callingUid = android.os.Binder.getCallingUid()
                    if (callingUid == 1001 || callingUid == 1073 || callingUid == 0) {
                        return@intercept chain.proceed()
                    }
                    val decoyId = decoyUserId()
                    if (shouldFilterDecoyUser(decoyId)) {
                        Log.i("NileHook", "UMS.getUserSwitchability intercepted -> returning 1 (SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED)")
                        return@intercept 1
                    }
                    chain.proceed()
                }
            }

            umsClass.declaredMethods.filter { it.name == "getMaxSupportedUsers" }.forEach { method ->
                hook(method).intercept { chain ->
                    val callingUid = android.os.Binder.getCallingUid()
                    if (callingUid == 1001 || callingUid == 1073 || callingUid == 0) {
                        return@intercept chain.proceed()
                    }
                    val decoyId = decoyUserId()
                    if (shouldFilterDecoyUser(decoyId)) {
                        return@intercept 1
                    }
                    chain.proceed()
                }
            }

            umsClass.declaredMethods.filter { it.name == "dump" }.forEach { method ->
                hook(method).intercept { chain ->
                    val decoyId = decoyUserId()
                    if (shouldFilterDecoyUser(decoyId)) {
                        val pw = chain.args.firstOrNull { it is java.io.PrintWriter } as? java.io.PrintWriter
                        if (pw != null) {
                            val filteringPw = object : java.io.PrintWriter(pw) {
                                override fun println(x: String?) {
                                    if (x != null && (x.contains("UserInfo{$decoyId:") || x.contains("User $decoyId:"))) return
                                    super.println(x)
                                }
                                override fun write(s: String, off: Int, len: Int) {
                                    val sub = s.substring(off, off + len)
                                    if (sub.contains("UserInfo{$decoyId:") || sub.contains("User $decoyId:")) return
                                    super.write(s, off, len)
                                }
                            }
                            val newArgs = chain.args.map { if (it === pw) filteringPw else it }.toTypedArray()
                            return@intercept chain.proceed(newArgs)
                        }
                    }
                    chain.proceed()
                }
            }
            Log.i("NileHook", "UserManagerService hooks installed successfully")
        } catch (e: Throwable) {
            Log.e("NileHook", "Failed to hook UserManagerService", e)
        }
    }

    private fun hookSystemServer(cl: ClassLoader) {
        Log.i("NileHook", "hookSystemServer — installing PMS, ComputerEngine, and UMS hooks")
        try {
            val parceled = try {
                cl.loadClass("android.content.pm.ParceledListSlice")
            } catch (e: Exception) {
                null
            }
            for (name in listOf(
                "com.android.server.pm.PackageManagerService",
                "com.android.server.pm.ComputerEngine",
            )) {
                try {
                    val clazz = loadServiceClass(cl, name)
                    hookPm(clazz, parceled)
                    hookDirectQueries(clazz, throwOnMiss = false)
                    Log.i("NileHook", "Hooked $name successfully")
                } catch (e: Exception) {
                    Log.w("NileHook", "Skipped class $name: ${e.message}")
                }
            }

            hookUserManager(cl)
            hookLockCredential(cl)
            hookUserSwitchingDialog(cl)
            hookUsageStatsService(cl)
            hookRecentTasks(cl)
            hookNotificationManager(cl)
        } catch (e: Exception) {
            Log.e("NileHook", "hookSystemServer failed", e)
        }
    }

    private fun hookUsageStatsService(cl: ClassLoader) {
        try {
            val parceledClass = try { cl.loadClass("android.content.pm.ParceledListSlice") } catch (e: Exception) { null }
            val ussClassNames = listOf(
                "com.android.server.usage.UsageStatsService",
                "com.android.server.usage.UsageStatsDatabase"
            )
            for (className in ussClassNames) {
                try {
                    val clazz = loadServiceClass(cl, className)
                    for (method in clazz.declaredMethods) {
                        if (method.name in setOf("queryUsageStats", "queryEvents", "queryEventsForUser", "queryEventsForPackage", "queryAndAggregateUsageStats")) {
                            hook(method).intercept { chain ->
                                try {
                                    val result = chain.proceed()
                                    if (isUnlocked() || result == null) return@intercept result
                                    filterUsageStatsResult(parceledClass, result)
                                } catch (t: Throwable) {
                                    chain.proceed()
                                }
                            }
                        } else if (method.name == "reportEvent") {
                            hook(method).intercept { chain ->
                                try {
                                    if (!isUnlocked()) {
                                        val event = chain.args.firstOrNull { it != null && it.javaClass.name.contains("Event") }
                                        if (event != null) {
                                            val pkg = try {
                                                event.javaClass.getMethod("getPackageName").invoke(event) as? String
                                                    ?: event.javaClass.getField("mPackage").get(event) as? String
                                            } catch (e: Exception) { null }
                                            if (pkg != null && pkg in hidden) {
                                                if (method.returnType == Void.TYPE || method.returnType.name == "void") {
                                                    return@intercept null
                                                }
                                            }
                                        }
                                    }
                                    chain.proceed()
                                } catch (t: Throwable) {
                                    chain.proceed()
                                }
                            }
                        }
                    }
                    Log.i("NileHook", "Hooked $className successfully")
                } catch (e: Exception) {
                    Log.w("NileHook", "Skipped $className: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("NileHook", "hookUsageStatsService failed", e)
        }
    }

    private fun filterUsageStatsResult(parceledClass: Class<*>?, result: Any?): Any? {
        if (result == null || isUnlocked()) return result
        try {
            if (parceledClass != null && parceledClass.isInstance(result)) {
                val list = parceledClass.getMethod("getList").invoke(result) as? List<*> ?: return result
                val filtered = list.filter { item ->
                    val pkg = try {
                        item?.javaClass?.getMethod("getPackageName")?.invoke(item) as? String
                            ?: item?.javaClass?.getField("mPackageName")?.get(item) as? String
                    } catch (e: Exception) { null }
                    pkg == null || pkg !in hidden
                }
                return parceledClass.getConstructor(List::class.java).newInstance(filtered)
            }
            if (result is List<*>) {
                return result.filter { item ->
                    val pkg = try {
                        item?.javaClass?.getMethod("getPackageName")?.invoke(item) as? String
                            ?: item?.javaClass?.getField("mPackageName")?.get(item) as? String
                    } catch (e: Exception) { null }
                    pkg == null || pkg !in hidden
                }
            }
            if (result is Map<*, *>) {
                return result.filterKeys { key -> (key as? String) !in hidden }
            }
        } catch (e: Exception) {
            Log.w("NileHook", "filterUsageStatsResult error", e)
        }
        return result
    }

    private fun isTaskInfoHidden(task: Any?): Boolean {
        if (task == null) return false
        val pkg = try {
            val baseIntent = task.javaClass.getField("baseIntent").get(task) as? android.content.Intent
            val realActivity = task.javaClass.getField("realActivity").get(task) as? android.content.ComponentName
            val origActivity = task.javaClass.getField("origActivity").get(task) as? android.content.ComponentName
            val topActivity = task.javaClass.getField("topActivity").get(task) as? android.content.ComponentName
            val baseActivity = task.javaClass.getField("baseActivity").get(task) as? android.content.ComponentName

            baseIntent?.component?.packageName ?: realActivity?.packageName ?: origActivity?.packageName ?: topActivity?.packageName ?: baseActivity?.packageName
        } catch (e: Exception) {
            try {
                val baseIntent = task.javaClass.getMethod("getBaseIntent").invoke(task) as? android.content.Intent
                baseIntent?.component?.packageName
            } catch (e2: Exception) { null }
        }
        return pkg != null && pkg in hidden
    }

    private fun filterRecentTasksResult(parceledClass: Class<*>?, result: Any?): Any? {
        if (result == null || isUnlocked()) return result
        try {
            if (parceledClass != null && parceledClass.isInstance(result)) {
                val list = parceledClass.getMethod("getList").invoke(result) as? List<*> ?: return result
                val filtered = list.filter { item -> !isTaskInfoHidden(item) }
                return parceledClass.getConstructor(List::class.java).newInstance(filtered)
            }
            if (result is List<*>) {
                return result.filter { item -> !isTaskInfoHidden(item) }
            }
        } catch (e: Exception) {
            Log.w("NileHook", "filterRecentTasksResult error", e)
        }
        return result
    }

    private fun hookRecentTasks(cl: ClassLoader) {
        try {
            val parceledClass = try { cl.loadClass("android.content.pm.ParceledListSlice") } catch (e: Exception) { null }
            val classNames = listOf(
                "com.android.server.wm.ActivityTaskManagerService",
                "com.android.server.wm.RecentTasks"
            )
            for (className in classNames) {
                try {
                    val clazz = loadServiceClass(cl, className)
                    val recentMethods = clazz.declaredMethods.filter { it.name in setOf("getRecentTasks", "getRecentTasksImpl") }
                    for (method in recentMethods) {
                        hook(method).intercept { chain ->
                            try {
                                val result = chain.proceed()
                                if (isUnlocked() || result == null) return@intercept result
                                filterRecentTasksResult(parceledClass, result)
                            } catch (t: Throwable) {
                                chain.proceed()
                            }
                        }
                    }
                    Log.i("NileHook", "Hooked $className.getRecentTasks successfully")
                } catch (e: Exception) {
                    Log.w("NileHook", "Skipped $className: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("NileHook", "hookRecentTasks failed", e)
        }
    }

    private fun hookNotificationManager(cl: ClassLoader) {
        try {
            val clazz = loadServiceClass(cl, "com.android.server.notification.NotificationManagerService")
            val enqueueMethods = clazz.declaredMethods.filter { 
                it.name in setOf("enqueueNotificationInternal", "enqueueNotificationWithTag", "enqueueNotification") 
            }
            for (method in enqueueMethods) {
                hook(method).intercept { chain ->
                    try {
                        if (!isUnlocked()) {
                            val pkg = chain.args.firstOrNull { it is String } as? String
                            if (pkg != null && pkg in hidden) {
                                Log.i("NileHook", "Suppressed notification for hidden package: $pkg")
                                if (method.returnType == Void.TYPE || method.returnType.name == "void") {
                                    return@intercept null
                                }
                            }
                        }
                        chain.proceed()
                    } catch (t: Throwable) {
                        chain.proceed()
                    }
                }
            }
            Log.i("NileHook", "Hooked NotificationManagerService successfully")
        } catch (e: Exception) {
            Log.w("NileHook", "hookNotificationManager skipped: ${e.message}")
        }
    }

    private fun hiddenDirectoriesAndFiles(): Set<String> {
        val dirs = mutableSetOf<String>()
        try {
            val json = readConfigJsonObject() ?: org.json.JSONObject(com.thenile.vault.root.ConfigCrypto.decrypt(java.io.File("/data/system/thenile_config.json").readText()))
            val targetDirs = json.optJSONArray("targetDirectories")
            if (targetDirs != null) {
                for (i in 0 until targetDirs.length()) dirs.add(targetDirs.getString(i))
            }
            val targetFiles = json.optJSONArray("targetFiles")
            if (targetFiles != null) {
                for (i in 0 until targetFiles.length()) dirs.add(targetFiles.getString(i))
            }
            val vaults = json.optJSONArray("vaults")
            if (vaults != null) {
                for (i in 0 until vaults.length()) {
                    val p = vaults.getJSONObject(i)
                    p.optJSONArray("directories")?.let { a -> for (j in 0 until a.length()) dirs.add(a.getString(j)) }
                    p.optJSONArray("files")?.let { a -> for (j in 0 until a.length()) dirs.add(a.getString(j)) }
                }
            }
        } catch (e: Exception) {}
        return dirs
    }

    private fun filterMediaCursor(cursor: android.database.Cursor, hiddenTargets: Set<String>): android.database.Cursor {
        try {
            val dataCol = cursor.getColumnIndex("_data")
            val relativePathCol = cursor.getColumnIndex("relative_path")
            if (dataCol == -1 && relativePathCol == -1) return cursor

            val matrix = android.database.MatrixCursor(cursor.columnNames)
            val count = cursor.columnCount
            while (cursor.moveToNext()) {
                val data = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                val rel = if (relativePathCol != -1) cursor.getString(relativePathCol) ?: "" else ""

                val isHidden = hiddenTargets.any { target ->
                    (data.isNotEmpty() && (data.contains(target) || target.contains(data))) ||
                    (rel.isNotEmpty() && (rel.contains(target) || target.contains(rel)))
                }

                if (!isHidden) {
                    val row = arrayOfNulls<Any>(count)
                    for (i in 0 until count) {
                        when (cursor.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> row[i] = null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> row[i] = cursor.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> row[i] = cursor.getDouble(i)
                            android.database.Cursor.FIELD_TYPE_STRING -> row[i] = cursor.getString(i)
                            android.database.Cursor.FIELD_TYPE_BLOB -> row[i] = cursor.getBlob(i)
                        }
                    }
                    matrix.addRow(row)
                }
            }
            cursor.close()
            return matrix
        } catch (e: Exception) {
            Log.w("NileHook", "filterMediaCursor failed, returning original", e)
            return cursor
        }
    }

    private fun hookMediaProvider(cl: ClassLoader) {
        try {
            val mpClass = cl.loadClass("com.android.providers.media.MediaProvider")
            val queryMethods = mpClass.declaredMethods.filter { it.name == "query" }
            for (method in queryMethods) {
                hook(method).intercept { chain ->
                    val cursor = chain.proceed() as? android.database.Cursor
                    if (isUnlocked() || cursor == null) return@intercept cursor

                    val hiddenTargets = hiddenDirectoriesAndFiles()
                    if (hiddenTargets.isEmpty()) return@intercept cursor

                    filterMediaCursor(cursor, hiddenTargets)
                }
            }
            Log.i("NileHook", "Hooked MediaProvider.query successfully")
        } catch (e: Exception) {
            Log.w("NileHook", "hookMediaProvider skipped: ${e.message}")
        }
    }

    private fun isSuppressSwitchAnimationEnabled(): Boolean = try {
        val json = readConfigJsonObject() ?: return true
        json.optBoolean("suppressUserSwitchAnimation", true)
    } catch (e: Exception) {
        true
    }

    private fun hookUserSwitchingDialog(cl: ClassLoader) {
        try {
            // 1. Hook UserController$Injector (Android 12-15 system_server)
            try {
                val injectorClass = loadServiceClass(cl, "com.android.server.am.UserController\$Injector")
                for (m in injectorClass.declaredMethods) {
                    if (m.name == "showUserSwitchingDialog") {
                        hook(m).intercept { chain ->
                            if (isSuppressSwitchAnimationEnabled()) {
                                Log.i("NileHook", "UserController.Injector.showUserSwitchingDialog suppressed! Running callback directly.")
                                val lastArg = chain.args.lastOrNull()
                                if (lastArg is Runnable) {
                                    lastArg.run()
                                }
                                return@intercept null
                            }
                            chain.proceed()
                        }
                        Log.i("NileHook", "Hooked UserController.Injector.showUserSwitchingDialog successfully")
                    }
                }
            } catch (e: Exception) {
                Log.w("NileHook", "Failed to hook UserController.Injector: ${e.message}")
            }

            // 2. Hook UserController methods
            try {
                val userControllerClass = loadServiceClass(cl, "com.android.server.am.UserController")
                val isUiEnabledMethods = userControllerClass.declaredMethods.filter { it.name == "isUserSwitchUiEnabled" }
                for (m in isUiEnabledMethods) {
                    hook(m).intercept { chain ->
                        if (isSuppressSwitchAnimationEnabled()) {
                            Log.i("NileHook", "UserController.isUserSwitchUiEnabled -> false (suppressing switch UI)")
                            return@intercept false
                        }
                        chain.proceed()
                    }
                }
            } catch (e: Exception) {
                Log.w("NileHook", "Failed to hook UserController: ${e.message}")
            }

            // 3. Hook UserSwitchingDialog directly (handles show(Runnable) and show())
            try {
                val clazz = loadServiceClass(cl, "com.android.server.am.UserSwitchingDialog")
                val showMethods = (clazz.declaredMethods + clazz.methods).distinct().filter { it.name == "show" }
                for (method in showMethods) {
                    try {
                        hook(method).intercept { chain ->
                            if (isSuppressSwitchAnimationEnabled()) {
                                Log.i("NileHook", "Suppressed UserSwitchingDialog.show animation")
                                for (arg in chain.args) {
                                    if (arg is Runnable) {
                                        arg.run()
                                    }
                                }
                                return@intercept null
                            }
                            chain.proceed()
                        }
                        Log.i("NileHook", "Hooked UserSwitchingDialog.show successfully")
                    } catch (e: Exception) {
                        Log.w("NileHook", "Failed to hook UserSwitchingDialog.show: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w("NileHook", "Skipped UserSwitchingDialog hook: ${e.message}")
            }

            // 3. Hook ActivityManagerService switch dialog methods if present
            try {
                val amsClass = loadServiceClass(cl, "com.android.server.am.ActivityManagerService")
                val amsDialogMethods = amsClass.declaredMethods.filter { it.name in setOf("showUserSwitchDialog", "showUserSwitchingDialog") }
                for (m in amsDialogMethods) {
                    hook(m).intercept { chain ->
                        if (isSuppressSwitchAnimationEnabled()) {
                            Log.i("NileHook", "ActivityManagerService.${m.name} -> suppressed")
                            return@intercept null
                        }
                        chain.proceed()
                    }
                }
            } catch (e: Exception) {}
        } catch (e: Exception) {
            Log.e("NileHook", "hookUserSwitchingDialog failed", e)
        }
    }

    @SuppressLint("NewApi")
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        Log.i("NileHook", "DIAG onSystemServerStarting fired, uid=${android.os.Process.myUid()}")
        super.onSystemServerStarting(param)
        hookSystemServer(param.classLoader)
    }
}
