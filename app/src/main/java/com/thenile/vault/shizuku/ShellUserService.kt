package com.thenile.vault.shizuku

/** Runs inside its own process, started by Shizuku at shell UID (2000) — see
 *  ShizukuShell.bindSync(). A plain ProcessBuilder here already runs as shell, no further
 *  privilege dance needed; the AIDL round trip is just to get the result back to Nile's own
 *  process. Combines stdout+stderr (redirectErrorStream) since IShellService returns one string —
 *  callers only need pass/fail plus log text, not a clean stdout/stderr split (see StorageMountManager.sh). */
class ShellUserService : IShellService.Stub() {
    override fun exec(script: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            "$output\n$EXIT_MARKER$code"
        } catch (e: Throwable) {
            "${e.message}\n${EXIT_MARKER}-1"
        }
    }

    companion object {
        const val EXIT_MARKER = "__THENILE_EXIT__:"
    }
}
