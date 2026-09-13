package com.thenile.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thenile.vault.root.AccountVault
import com.topjohnwu.superuser.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real AccountVault snapshot -> remove -> restore path against a SYNTHETIC account
 *  planted in the actual accounts DBs (no real account is touched; it's cleaned up in finally).
 *  reloadFramework() is deliberately NOT called — it soft-reboots the UI and would kill the test;
 *  the DB layer is what this verifies. Needs root. */
@RunWith(AndroidJUnit4::class)
class AccountVaultDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val NS = "nsenter -t 1 -m --"
    private val name = "probe@nile.test"
    private val type = "com.nile.test"
    private val ce = "/data/system_ce/0/accounts_ce.db"
    private val de = "/data/system_de/0/accounts_de.db"

    private fun ce(sql: String) = Shell.cmd("$NS sqlite3 '$ce' \"$sql\"").exec()
    private fun de(sql: String) = Shell.cmd("$NS sqlite3 '$de' \"$sql\"").exec()
    private fun ceVal(sql: String) = ce(sql).out.firstOrNull()?.trim() ?: ""

    @Test
    fun snapshotRemoveRestoreRoundTrip() {
        assumeTrue("needs root", Shell.getShell().isRoot)
        assumeTrue("accounts DBs must exist", Shell.cmd("$NS test -f '$ce' && $NS test -f '$de'").exec().isSuccess)

        val dir = ctx.cacheDir.path
        try {
            // Plant a synthetic account (id 90001) with children in both DBs.
            ce("DELETE FROM accounts WHERE name='$name';")
            de("DELETE FROM accounts WHERE name='$name';")
            ce("INSERT INTO accounts(_id,name,type,password) VALUES(90001,'$name','$type','pw-secret');")
            ce("INSERT INTO authtokens(accounts_id,type,authtoken) VALUES(90001,'oauth2','TOK-XYZ');")
            ce("INSERT INTO extras(accounts_id,key,value) VALUES(90001,'services','mail');")
            de("INSERT INTO accounts(_id,name,type) VALUES(90001,'$name','$type');")
            de("INSERT INTO visibility(accounts_id,_package,value) VALUES(90001,'com.android.chrome',1);")
            assertEquals("planted", "pw-secret", ceVal("SELECT password FROM accounts WHERE name='$name';"))

            assertTrue("snapshot should succeed", AccountVault.snapshot(0, listOf(name), dir))
            assertTrue("ce snapshot file exists",
                Shell.cmd("$NS test -f '$dir/accounts_u0.ce.sql'").exec().isSuccess)

            assertTrue("remove should report a change", AccountVault.remove(0, listOf(name)))
            assertEquals("account gone after remove", "0",
                ceVal("SELECT count(*) FROM accounts WHERE name='$name';"))
            assertEquals("children cascaded", "0",
                ceVal("SELECT count(*) FROM authtokens WHERE accounts_id=90001;"))

            assertTrue("restore should apply", AccountVault.restore(0, dir))
            assertEquals("account back", "pw-secret", ceVal("SELECT password FROM accounts WHERE name='$name';"))
            assertEquals("token back", "TOK-XYZ", ceVal("SELECT authtoken FROM authtokens WHERE accounts_id=90001;"))
            assertEquals("extra back", "mail", ceVal("SELECT value FROM extras WHERE accounts_id=90001;"))
            assertEquals("de visibility back", "1", ceVal("SELECT value FROM visibility WHERE accounts_id=90001;").ifEmpty {
                Shell.cmd("$NS sqlite3 '$de' \"SELECT value FROM visibility WHERE accounts_id=90001;\"").exec().out.firstOrNull()?.trim() ?: ""
            })
        } finally {
            ce("DELETE FROM accounts WHERE name='$name';")
            de("DELETE FROM accounts WHERE name='$name';")
            Shell.cmd("$NS rm -f '$dir/accounts_u0.ce.sql' '$dir/accounts_u0.de.sql'").exec()
        }
    }
}
