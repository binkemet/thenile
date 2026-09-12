package com.thenile.vault

import com.thenile.vault.root.AppDataVault
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

/** Command-construction is the only part testable off-device (tar/chown/restorecon need root).
 *  Guards the two things that silently corrupt a restore if they regress: the -C anchor (so paths
 *  land back in the right user dir) and the throwaway excludes (so cache doesn't bloat/clobber). */
class AppDataVaultTest {

    @Test
    fun tarCreate_anchorsToUserDirAndExcludesThrowaways() {
        val cmd = AppDataVault.tarCreateCmd("com.example.app", 0, "/data/local/tmp/x.tar")
        assertTrue("must pack relative to the user dir", cmd.contains("-C '/data/user/0'"))
        assertTrue("must pack the package tree", cmd.contains("'com.example.app'"))
        assertTrue("must exclude cache", cmd.contains("--exclude=com.example.app/cache"))
        assertTrue("must exclude code_cache", cmd.contains("--exclude=com.example.app/code_cache"))
        assertFalse("must not swallow stderr into the archive", cmd.contains("2>"))
    }

    @Test
    fun tarExtract_restoresToSameUserDir() {
        val cmd = AppDataVault.tarExtractCmd("/data/local/tmp/x.tar", 11)
        assertTrue(cmd.contains("-xf '/data/local/tmp/x.tar'"))
        assertTrue("must extract into the matching user dir", cmd.contains("-C '/data/user/11'"))
    }

    @Test
    fun parseApkPaths_keepsBaseAndSplitsDropsNoise() {
        val out = listOf(
            "package:/data/app/~~a==/com.x-1/base.apk",
            "package:/data/app/~~a==/com.x-1/split_config.arm64_v8a.apk",
            "",
            "some unrelated line",
            "package:/data/app/~~a==/com.x-1/oat"          // not an apk → dropped
        )
        val paths = AppDataVault.parseApkPaths(out)
        assertEquals(2, paths.size)
        assertTrue(paths.all { it.endsWith(".apk") })
        assertTrue(paths.any { it.contains("base.apk") })
        assertTrue(paths.any { it.contains("split_config") })
    }
}
