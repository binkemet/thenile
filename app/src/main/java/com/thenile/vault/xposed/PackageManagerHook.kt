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
    private var lastCheck = 0L

    private fun refreshConfigIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastCheck <= 5000) return
        lastCheck = now
        try {
            val file = java.io.File("/data/system/thenile_config.json")
            if (file.exists()) {
                val json = org.json.JSONObject(file.readText())
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
            }
        } catch (e: Exception) {}
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
        is android.content.pm.ResolveInfo -> item.activityInfo?.packageName
        else -> null
    }

    /** Strip hidden packages from a returned List or ParceledListSlice; pass through when unlocked. */
    private fun filterResult(parceledClass: Class<*>?, result: Any?): Any? {
        if (result == null || isUnlocked()) return result
        if (parceledClass != null && parceledClass.isInstance(result)) {
            return try {
                val list = parceledClass.getMethod("getList").invoke(result) as? List<*> ?: return result
                val kept = list.filter { pkgNameOf(it) !in hidden }
                parceledClass.getConstructor(List::class.java).newInstance(kept)
            } catch (e: Exception) {
                result
            }
        }
        return if (result is List<*>) result.filter { pkgNameOf(it) !in hidden } else result
    }

    private fun hookPm(clazz: Class<*>, parceledClass: Class<*>?) {
        clazz.declaredMethods.filter { it.name in pmMethods }.forEach { method ->
            hook(method).intercept { chain -> filterResult(parceledClass, chain.proceed()) }
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
        Log.i("NileHook", "onPackageLoaded: ${param.packageName}")
        if (param.packageName == "com.android.systemui") hookLockCredential(param.defaultClassLoader)
        if (param.packageName in CALCULATOR_PACKAGES) hookCalculatorTrigger(param.defaultClassLoader)
        if (!param.isFirstPackage) return
        try {
            val appPm = param.defaultClassLoader.loadClass("android.app.ApplicationPackageManager")
            hookPm(appPm, null)
            hookDirectQueries(appPm, throwOnMiss = true)
            Log.i("NileHook", "ApplicationPackageManager hooked in ${param.packageName}")
        } catch (e: Exception) {
            Log.e("NileHook", "in-process PM hook failed in ${param.packageName}", e)
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
        if (!file.exists()) null else {
            val json = org.json.JSONObject(file.readText())
            val mode = json.optString("decoyLockScreenMode", "off")
            val arr = json.optJSONArray("decoyCodes")
            val codes = mutableSetOf<String>()
            if (arr != null) for (i in 0 until arr.length()) codes.add(arr.getString(i))
            val limit = json.optInt("decoyUnlockLimit", 1)
            Triple(mode, codes, limit)
        }
    } catch (e: Exception) {
        null
    }

    /** packages / directories / (target,dummy) pairs to hide for one decoy code, read from the
     *  same config file SettingsManager.syncToSystem() already writes on every settings change. */
    private fun decoyHideDataFor(code: String): Triple<List<String>, List<String>, List<Pair<String, String>>>? = try {
        val json = org.json.JSONObject(java.io.File("/data/system/thenile_config.json").readText())
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
        val dummies = mutableListOf<Pair<String, String>>()
        found.optJSONArray("dummyDirectories")?.let { a ->
            for (i in 0 until a.length()) {
                val d = a.getJSONObject(i)
                dummies.add(d.getString("target") to d.getString("dummy"))
            }
        }
        Triple(pkgs, dirs, dummies)
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
            val (packages, directories, dummies) = decoyHideDataFor(code) ?: Triple(emptyList(), emptyList(), emptyList())
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
            for ((target, dummy) in dummies) {
                val t = sq(target); val dm = sq(dummy)
                sb.append("mkdir -p ").append(t).append(" ").append(dm).append("\n")
                sb.append("nsenter -t 1 -m -- mount --bind ").append(dm).append(" ").append(t).append("\n")
            }
            // TraceCleaner-equivalent cleanup.
            sb.append("rm -rf /data/system/recent_images/* /data/system_ce/0/snapshots/* /data/system/usagestats/* /data/system/graphicsstats/*\n")
            sb.append("rm -f /data/system/package-usage.list /data/system/package-dex-usage.list /data/system/package-cstats.list\n")
            sb.append("sync; echo 1 > /proc/sys/vm/drop_caches\n")
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

    private fun hookLockCredential(cl: ClassLoader) {
        try {
            val lpu = cl.loadClass("com.android.internal.widget.LockPatternUtils")
            val methods = lpu.declaredMethods.filter { it.name == "checkCredential" }
            if (methods.isEmpty()) {
                Log.w("NileHook", "checkCredential not found on LockPatternUtils")
                return
            }
            for (method in methods) {
                hook(method).intercept { chain ->
                    Log.i("NileHook", "checkCredential intercepted, args=${chain.args.size}")
                    val (mode, codes, unlockLimit) = decoyLockConfig() ?: return@intercept chain.proceed()
                    Log.i("NileHook", "config: mode=$mode codes=$codes unlockLimit=$unlockLimit")
                    if (mode == "off" || codes.isEmpty()) return@intercept chain.proceed()

                    val cred = chain.getArg(0)
                    val bytes = try {
                        cred?.javaClass?.getMethod("getCredential")?.invoke(cred) as? ByteArray
                    } catch (e: Exception) {
                        Log.e("NileHook", "getCredential reflection failed", e)
                        null
                    }
                    val typed = bytes?.let { String(it, Charsets.US_ASCII) }
                    Log.i("NileHook", "typed credential len=${bytes?.size}")
                    if (typed == null || typed !in codes) return@intercept chain.proceed()

                    when (mode) {
                        "one_time_unlock" -> {
                            // Deliberately does NOT touch the credential at all — no substitution,
                            // no fabricated pass/fail. The real, unmodified credential check below
                            // still runs and fails on its own (the typed decoy genuinely isn't the
                            // real PIN); the actual unlock comes from triggerOneTimeUnlock()
                            // independently, via KeyguardUpdateMonitor, not from this check passing.
                            if (!hasOneTimeUnlockUsesLeft(unlockLimit)) {
                                Log.i("NileHook", "one_time_unlock: no uses left (limit=$unlockLimit), treating as normal wrong PIN")
                                return@intercept chain.proceed()
                            }
                            Log.i("NileHook", "one_time_unlock code MATCHED — hiding + triggering unlock (limit=$unlockLimit)")
                            triggerDecoyHide(typed, recordOneTimeUnlockUse = true)
                            triggerOneTimeUnlock(cl)
                            return@intercept chain.proceed()
                        }
                        "fake_wrong_pin" -> {
                            Log.i("NileHook", "decoy code MATCHED — hiding, substituting bogus credential")
                            triggerDecoyHide(typed)
                        }
                        else -> return@intercept chain.proceed()
                    }
                    val type = try {
                        cred.javaClass.getMethod("getType").invoke(cred) as? Int
                    } catch (e: Exception) {
                        Log.e("NileHook", "getType reflection failed", e)
                        null
                    }
                    Log.i("NileHook", "credential type=$type, cred class=${cred.javaClass.name}")
                    val bogus = try {
                        val ctor = cred.javaClass.getDeclaredConstructor(Int::class.javaPrimitiveType, CharSequence::class.java)
                        ctor.isAccessible = true
                        ctor.newInstance(type, BOGUS_CREDENTIAL)
                    } catch (e: Exception) {
                        Log.e("NileHook", "bogus credential construction failed — falling back to UNMODIFIED proceed (this is the risky path)", e)
                        null
                    }
                    if (bogus == null) {
                        Log.w("NileHook", "bogus==null, calling chain.proceed() with ORIGINAL unmodified args")
                        return@intercept chain.proceed()
                    }
                    Log.i("NileHook", "bogus credential built OK: ${bogus.javaClass.name}")
                    val args = chain.args.toMutableList()
                    args[0] = bogus
                    Log.i("NileHook", "calling chain.proceed(modifiedArgs)")
                    val result = chain.proceed(args.toTypedArray())
                    Log.i("NileHook", "proceed(modifiedArgs) returned: $result")
                    result
                }
            }
            Log.i("NileHook", "LockPatternUtils.checkCredential hooked (${methods.size} overload(s))")
        } catch (e: Exception) {
            Log.e("NileHook", "lock credential hook failed", e)
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

    private fun calculatorTriggerExpression(): String = try {
        val json = org.json.JSONObject(java.io.File("/data/system/thenile_config.json").readText())
        normalizeCalcText(json.optString("calculatorTriggerExpression", ""))
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
                        val trigger = calculatorTriggerExpression()
                        val typed = normalizeCalcText(chain.getArg(0) as? CharSequence)
                        val view = chain.getThisObject()
                        if (trigger.isNotEmpty() && typed == trigger) {
                            if (view != null && lastCalculatorMatch[view] != typed) {
                                lastCalculatorMatch[view] = typed
                                Log.i("NileHook", "calculator trigger matched, opening Admin")
                                launchAdminFromCalculator()
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

    private fun launchAdminFromCalculator() {
        try {
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

    companion object {
        // ponytail: fixed sentinel, not random — the real check only needs it to NOT equal the
        // stored credential, and a fixed string is enough since it's never persisted or compared
        // to anything but the actual gatekeeper/synthetic-password hash.
        private const val BOGUS_CREDENTIAL = " nile_decoy_reject_ "

        private val CALCULATOR_PACKAGES = setOf("com.android.calculator2", "com.google.android.calculator")
    }

    @SuppressLint("NewApi")
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        Log.i("NileHook", "onSystemServerStarting — installing PMS & ComputerEngine hooks")
        try {
            val cl = param.classLoader
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
                    val clazz = cl.loadClass(name)
                    hookPm(clazz, parceled)
                    hookDirectQueries(clazz, throwOnMiss = false)
                    Log.i("NileHook", "Hooked $name successfully")
                } catch (e: Exception) {
                    Log.w("NileHook", "Skipped class $name: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("NileHook", "system_server PMS hook failed", e)
        }
    }
}
