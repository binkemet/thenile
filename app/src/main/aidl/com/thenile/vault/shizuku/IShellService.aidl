package com.thenile.vault.shizuku;

// Bound via Shizuku.bindUserService — runs in its own process at shell UID (2000), not Nile's
// app UID. One method rather than separate stdout/stderr/exitCode calls to keep this to a single
// round trip; ShizukuShell.exec() parses the trailing exit-code marker back out.
interface IShellService {
    String exec(String script);
}
