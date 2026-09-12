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

Since v1.1 the config file is **encrypted at rest** (so the decoy PINs and vault layout aren't
sitting in a world-readable plaintext file). The blob is `NILEC1:` + hex(`iv‖ciphertext‖tag`),
AES-256/GCM, with a key of `SHA-256("th3n1l3::cfg::v1::keyguard-hook-shared-secret")` (12-byte IV,
128-bit tag). Decrypt before parsing; treat a blob that does *not* start with `NILEC1:` as legacy
plaintext JSON. This is obfuscation-grade (the key ships in the code) — its job is to defeat file
inspection / `adb pull` / backup extraction, not a reverse-engineer.

```java
// The Nile: decoy-PIN interception. entered = the PIN/password the user just typed.
try {
    java.io.File cfg = new java.io.File("/data/system/thenile_config.json");
    if (cfg.exists()) {
        String blob = new String(java.nio.file.Files.readAllBytes(cfg.toPath())).trim();
        String plain;
        if (blob.startsWith("NILEC1:")) {
            String hex = blob.substring(7);
            byte[] buf = new byte[hex.length() / 2];
            for (int i = 0; i < buf.length; i++)
                buf[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            byte[] key = java.security.MessageDigest.getInstance("SHA-256")
                .digest("th3n1l3::cfg::v1::keyguard-hook-shared-secret".getBytes("UTF-8"));
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, buf, 0, 12)); // first 12 bytes = IV
            plain = new String(c.doFinal(buf, 12, buf.length - 12), "UTF-8");
        } else {
            plain = blob; // legacy plaintext
        }
        org.json.JSONObject j = new org.json.JSONObject(plain);
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
