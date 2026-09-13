package com.thenile.vault.root

import android.util.Base64
import android.util.Log

/** Snapshot / remove / restore of device accounts at the accounts-DB level (root only), so a vault
 *  can make Google/etc. accounts VANISH in decoy/locked state and bring them back — with their
 *  cached password, auth tokens, and settings intact — on real unlock. Distinct from
 *  [com.thenile.vault.state.Vault.removeAccounts], which is a permanent AccountManager removal with
 *  no restore (kept as-is).
 *
 *  Accounts live in two SQLite DBs per user: accounts_ce.db (name/type/password, authtokens, extras
 *  — CE-encrypted, needs the user unlocked) and accounts_de.db (name/type, grants, visibility). A
 *  given account shares one _id across both. We dump the target rows as INSERT statements (children
 *  keyed by accounts_id) into the hidden volume; DELETE cascades through the DBs' own triggers;
 *  restore re-applies the INSERTs. The DB round-trip is verified on-device (name/type/password/
 *  token/extras all preserved).
 *
 *  AccountManagerService caches accounts in memory, so a DB edit isn't visible until the framework
 *  reloads — [reloadFramework] does a `stop; start` soft-restart. Two honest caveats:
 *   - that restart is a ~10-20s framework reboot (screen goes through boot, device re-locks);
 *   - auth tokens can expire server-side, so a provider may still force re-auth after restore —
 *     snapshot the authenticator app alongside (as a hiddenApp) for the best chance of a seamless
 *     return. */
object AccountVault {
    private const val TAG = "AccountVault"
    private const val NS = "nsenter -t 1 -m --"

    private fun ce(u: Int) = "/data/system_ce/$u/accounts_ce.db"
    private fun de(u: Int) = "/data/system_de/$u/accounts_de.db"
    private fun sqlEsc(s: String) = s.replace("'", "''")
    private fun nameInClause(names: List<String>) = names.joinToString(",") { "'${sqlEsc(it)}'" }

    /** Query helper: run one sqlite statement, return stdout lines (empty on any failure). */
    private fun q(db: String, vararg args: String): List<String> {
        val quoted = args.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val r = PrivilegedShell.exec("$NS sqlite3 '$db' $quoted")
        return if (r.isSuccess) r.out else emptyList()
    }

    /** Dump rows for [names] (all types matching each name) into [destDir] as two .sql files inside
     *  the mounted hidden volume. Returns true if the files were written (or nothing to snapshot). */
    fun snapshot(userId: Int, names: List<String>, destDir: String): Boolean {
        if (names.isEmpty()) return true
        val inClause = nameInClause(names)
        val ok1 = dumpDb(ce(userId), inClause, names, "$destDir/accounts_u$userId.ce.sql",
            childTables = listOf("authtokens", "extras"))
        val ok2 = dumpDb(de(userId), inClause, names, "$destDir/accounts_u$userId.de.sql",
            childTables = listOf("grants", "visibility"))
        return ok1 && ok2
    }

    private fun dumpDb(db: String, inClause: String, names: List<String>, destFile: String, childTables: List<String>): Boolean {
        val exists = PrivilegedShell.exec("$NS test -f '$db'").isSuccess
        if (!exists) return true
        val ids = q(db, "SELECT _id FROM accounts WHERE name IN ($inClause);").mapNotNull { it.trim().toIntOrNull() }
        val sql = StringBuilder("BEGIN;\n")
        // Clear any current rows for these accounts first so restore is idempotent (triggers cascade).
        for (n in names) sql.append("DELETE FROM accounts WHERE name='${sqlEsc(n)}';\n")
        for (id in ids) {
            q(db, ".mode insert accounts", "SELECT * FROM accounts WHERE _id=$id;").forEach { sql.append(it).append('\n') }
            for (t in childTables)
                q(db, ".mode insert $t", "SELECT * FROM $t WHERE accounts_id=$id;").forEach { sql.append(it).append('\n') }
        }
        sql.append("COMMIT;\n")
        // Write via base64 so no SQL content has to survive shell quoting.
        val b64 = Base64.encodeToString(sql.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return PrivilegedShell.exec("$NS sh -c 'echo $b64 | base64 -d > \"$destFile\"'").isSuccess
    }

    /** Remove [names] from the live DBs (cascades). Does NOT reload — the caller must call
     *  [reloadFramework] once, last, so the account actually disappears. Returns true if it removed
     *  anything worth reloading for. */
    fun remove(userId: Int, names: List<String>): Boolean {
        if (names.isEmpty()) return false
        val inClause = nameInClause(names)
        var touched = false
        for (db in listOf(ce(userId), de(userId))) {
            if (PrivilegedShell.exec("$NS test -f '$db'").isSuccess) {
                PrivilegedShell.exec("$NS sqlite3 '$db' \"DELETE FROM accounts WHERE name IN ($inClause);\"")
                touched = true
            }
        }
        if (touched) Log.w(TAG, "removed ${names.size} account(s) for user $userId")
        return touched
    }

    /** Re-apply the snapshot SQL from [srcDir]. Does NOT reload — caller calls [reloadFramework]
     *  last. Returns true if any snapshot was applied. */
    fun restore(userId: Int, srcDir: String): Boolean {
        var applied = false
        for (suffix in listOf("ce", "de")) {
            val sql = "$srcDir/accounts_u$userId.$suffix.sql"
            val db = if (suffix == "ce") ce(userId) else de(userId)
            if (PrivilegedShell.exec("$NS test -f '$sql'").isSuccess &&
                PrivilegedShell.exec("$NS test -f '$db'").isSuccess) {
                PrivilegedShell.exec("$NS sh -c 'sqlite3 \"$db\" < \"$sql\"'")
                applied = true
            }
        }
        if (applied) Log.w(TAG, "restored accounts for user $userId")
        return applied
    }

    /** Soft-restart the framework so AccountManagerService re-reads the DBs. Heavy (a ~10-20s
     *  reboot of the UI layer) but the only reliable way to flush its in-memory account cache.
     *  Kills this app too, so callers must invoke it LAST, after every other vault action. */
    fun reloadFramework() {
        PrivilegedShell.exec("$NS sh -c 'stop && start'")
    }
}
