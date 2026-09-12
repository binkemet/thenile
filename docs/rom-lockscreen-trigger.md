# Real-lockscreen decoy trigger (AOSP edition, no Xposed)

The `xposed` build intercepts the real lock screen by hooking `system_server`. The `aosp` build
can't hook, so the **ROM does the check itself** and hands off to Nile. This is the only way to read
the secure Keyguard PIN without Xposed — Android blocks accessibility/other apps from reading it, so
it has to be done inside the platform, which a ROM build already is.

## How it works

1. Nile (running as a normal/system app) already writes every decoy PIN + config to
   `/data/system/thenile_config.json` on every settings change (`SettingsManager.syncToSystem()`).
2. The ROM's Keyguard is patched: when a PIN is entered, compare it against that file's decoy PINs.
3. On a match, Keyguard broadcasts `com.thenile.vault.action.LOCKSCREEN_DECOY` with a `pin` extra.
   `LockscreenDecoyReceiver` picks it up and runs the same switch-user + vault-hide dispatch as the
   dialer codes. The broadcast requires `MANAGE_USERS` (signature|privileged) so only the platform
   can send it — a normal app can't spoof a decoy.

Whether Keyguard then shows "wrong PIN", proceeds to unlock into the decoy user, or stays locked is
the ROM's choice; the hide/switch happens regardless because the broadcast fires before unlock.

## The patch

In the password-check path — e.g. `KeyguardSecurityContainerController#reportSuccessfulUnlock` or
`KeyguardUpdateMonitor`, wherever the entered credential is available as a string — add:

```java
// The Nile: decoy-PIN interception. entered = the PIN/password the user just typed.
try {
    java.io.File cfg = new java.io.File("/data/system/thenile_config.json");
    if (cfg.exists()) {
        org.json.JSONObject j = new org.json.JSONObject(
            new String(java.nio.file.Files.readAllBytes(cfg.toPath())));
        org.json.JSONArray codes = j.optJSONArray("decoyCodes");
        boolean isDecoy = false;
        for (int i = 0; codes != null && i < codes.length(); i++) {
            if (entered.equals(codes.getString(i))) { isDecoy = true; break; }
        }
        // Per-profile decoy PINs live in j.getJSONArray("profiles")[k].getString("decoyPin").
        if (isDecoy) {
            Intent x = new Intent("com.thenile.vault.action.LOCKSCREEN_DECOY");
            x.setPackage("com.thenile.vault");
            x.putExtra("pin", entered);
            mContext.sendBroadcastAsUser(x, UserHandle.SYSTEM);
        }
    }
} catch (Throwable ignored) { /* never block real unlock on a parse error */ }
```

Notes:
- The sender must hold `MANAGE_USERS`. `system_server` does; SystemUI usually does. If you patch a
  process that doesn't, drop the `android:permission` on the receiver or use your own signature perm.
- Keep the `try/catch` total — a bad config file must never brick the lock screen.
- Match against `profiles[].decoyPin` too if you use per-profile PINs, not just `decoyCodes`.
- SELinux: `system_server`/SystemUI already read `/data/system` (`system_data_file`). If you sandbox
  Keyguard differently, allow it `read` on `thenile_config.json`.

That's the whole integration. Everything downstream (hide, decoy user switch, master-code unhide) is
already in the APK and identical to the dialer-code path.
