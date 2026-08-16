package com.thenile.vault

/**
 * Single source of truth shared by the receiver, the prompt UI and the LSPosed hook.
 * ponytail: hard-coded config, promote to a settings screen only if end-users must edit it.
 */
object Config {
    // Packages hidden while locked/decoy and re-homed onto the vault when unlocked.
    val TARGET_PACKAGES = emptyList<String>()

    // The vault app's own package. Hidden alongside the targets when not unlocked so it doesn't
    // show up in Settings > Apps as an obvious tell. No launcher + dialer-triggered, so hiding it
    // costs nothing; the SECRET_CODE receiver still resolves via queryBroadcastReceivers.
    const val SELF_PACKAGE = "com.thenile.vault"

    // Everything the LSPosed hook strips from package queries while not UNLOCKED.
    val HIDDEN_PACKAGES = TARGET_PACKAGES + SELF_PACKAGE

    // Volatile prop carrying the live state, shared by the state manager (setprop) and the hook.
    // Must stay under debug.* so a rooted setprop needs no custom sepolicy; named to not read as a
    // vault flag in a `getprop` dump.
    const val STATE_PROP = "debug.tc.mode"

    // Secret-dial hosts (the digits between *#*# and #*#*) mapped to an action.
    const val CODE_LOCK = "1111"
    const val CODE_DECOY = "1234"
    const val CODE_UNLOCK = "9876"
    const val CODE_DURESS = "0000"

    val KNOWN_CODES = setOf(CODE_LOCK, CODE_DECOY, CODE_UNLOCK, CODE_DURESS)
}
