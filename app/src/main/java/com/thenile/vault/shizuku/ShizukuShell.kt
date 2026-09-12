package com.thenile.vault.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.thenile.vault.AppContextProvider
import com.thenile.vault.root.ShellResult
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs shell commands at shell UID (2000) through Shizuku, for devices/sessions with no root
 *  (e.g. GrapheneOS + wireless debugging). Shizuku.newProcess isn't public API on the current
 *  shizuku-api version — this binds our own tiny AIDL service (ShellUserService) instead, which
 *  Shizuku starts in a separate process already running as shell; that process does the actual
 *  ProcessBuilder exec and hands the result back over the binder. */
object ShizukuShell {
    private const val TAG = "ShizukuShell"

    @Volatile private var service: IShellService? = null

    // Standard bindService semantics deliver onServiceConnected on the calling thread's own
    // Looper (falling back to main if the caller has none). exec() is called synchronously from
    // Compose onClick handlers — i.e. the main thread — so binding directly there and then
    // blocking that same thread on a latch is a self-deadlock: the callback that would unblock it
    // can never run while it's blocked (confirmed on-device: every call times out at exactly the
    // 5s latch limit, "Shizuku user service not bound"). A dedicated HandlerThread gives the bind
    // call its own Looper, so the callback lands there instead of wherever exec() was called from.
    private val handlerThread by lazy { HandlerThread("ShizukuShellBind").apply { start() } }
    private val bindHandler by lazy { Handler(handlerThread.looper) }

    private val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(AppContextProvider.appContext.packageName, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shizuku_shell")
            .debuggable(false)
            .version(1)
    }

    /** Binds (once, cached) and blocks the CALLING thread on a latch to keep exec() synchronous —
     *  same shape as PrivilegedShell's root path (Shell.cmd(...).exec()), so StorageMountManager/
     *  TraceCleaner/etc. didn't need reworking into a callback or coroutine style to gain the
     *  Shizuku tier. The bind call itself runs on bindHandler's thread (see above), not the
     *  caller's, so it's safe to call this from the main thread. */
    private fun bindSync(): IShellService? {
        service?.let { return it }
        synchronized(this) {
            service?.let { return it }
            val latch = CountDownLatch(1)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = binder?.let { IShellService.Stub.asInterface(it) }
                    latch.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }
            var bindFailed = false
            bindHandler.post {
                try {
                    Shizuku.bindUserService(args, connection)
                } catch (e: Throwable) {
                    Log.e(TAG, "bindUserService failed: ${e.message}", e)
                    bindFailed = true
                    latch.countDown()
                }
            }
            latch.await(5, TimeUnit.SECONDS)
            if (bindFailed) return null
            return service
        }
    }

    fun exec(script: String): ShellResult {
        val svc = bindSync() ?: return ShellResult(false, -1, emptyList(), listOf("Shizuku user service not bound"))
        return try {
            val raw = svc.exec(script)
            val idx = raw.lastIndexOf(ShellUserService.EXIT_MARKER)
            if (idx < 0) return ShellResult(false, -1, emptyList(), listOf("malformed response: $raw"))
            val code = raw.substring(idx + ShellUserService.EXIT_MARKER.length).trim().toIntOrNull() ?: -1
            val out = raw.substring(0, idx).trimEnd('\n').lines().filter { it.isNotEmpty() }
            ShellResult(code == 0, code, out, emptyList())
        } catch (e: Throwable) {
            Log.e(TAG, "exec failed: ${e.message}", e)
            service = null // stale binder (service process died) — force a rebind next call
            ShellResult(false, -1, emptyList(), listOf(e.message ?: "shizuku exec failed"))
        }
    }
}
