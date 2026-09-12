package com.thenile.vault.root

import com.thenile.vault.shizuku.ShizukuShell
import com.topjohnwu.superuser.Shell

data class ShellResult(val isSuccess: Boolean, val code: Int, val out: List<String>, val err: List<String>)

/** Drop-in for `Shell.cmd(...).exec()` that also works over Shizuku's shell-UID process when
 *  there's no root — same commands, just routed to whichever privilege tier is actually
 *  available. Commands that need CAP_SYS_ADMIN (mount, nsenter, losetup, mkfs, the dmcrypt
 *  helper) will simply fail under Shizuku exactly like they would running as plain shell over
 *  adb — that's a real ceiling, not a bug here; StorageMountManager routes those cases to
 *  SoftVault instead of expecting this to make them work. */
object PrivilegedShell {
    fun exec(vararg commands: String): ShellResult = when (PrivilegeManager.currentTier()) {
        PrivilegeTier.ROOT -> {
            val r = Shell.cmd(*commands).exec()
            ShellResult(r.isSuccess, r.code, r.out, r.err)
        }
        PrivilegeTier.SHIZUKU -> ShizukuShell.exec(commands.joinToString("\n"))
        PrivilegeTier.NONE -> ShellResult(false, -1, emptyList(), listOf("no privilege tier available"))
    }
}
