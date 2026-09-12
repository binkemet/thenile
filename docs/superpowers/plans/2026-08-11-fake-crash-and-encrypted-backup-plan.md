# Fake Crash Disguise & Encrypted Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a Fake Crash Disguise entry barrier and a native Rust-encrypted (`.nile`) Profile Backup & Restore system for The Nile Stealth Engine.

**Architecture:** 
1. `rust_crypto` C-dylib compiled via Cargo/JNI providing Argon2id key derivation and AES-256-GCM encryption/decryption for `.nile` backups.
2. `BackupManager.kt` handling SAF document creation/opening, JSON serialization, and profile deduplication.
3. `FakeCrashScreen.kt` implementing a 1.5-second long-press gesture detector with haptic feedback to bypass the fake system error screen.
4. `AdminActivity.kt` incorporating the settings toggles, SAF file launchers, and disguise gating.

**Tech Stack:** Kotlin, Jetpack Compose, Cargo/Rust (`aes-gcm`, `argon2`, `jni`), Android SAF (`CreateDocument`, `OpenDocument`).

## Global Constraints
- Native Rust crypto functions MUST be exposed via JNI as `Java_com_thenile_vault_backup_BackupManager_*`.
- Backup file header MUST be `NILE_V1` (7 bytes) followed by 16-byte salt, 12-byte IV, and AES-256-GCM ciphertext.
- Fake crash bypass MUST require a 1,500ms long press on the "Close app" button and trigger haptic feedback.

---

### Task 1: Native Rust JNI AES-256-GCM Backup Encryption (`rust_crypto` + `BackupManager.kt`)

**Files:**
- Modify: `app/rust_crypto/Cargo.toml`
- Modify: `app/rust_crypto/src/lib.rs`
- Create: `app/src/main/java/com/thenile/vault/backup/BackupManager.kt`
- Create: `app/src/test/java/com/thenile/vault/BackupTest.kt`

**Interfaces:**
- Produces: `BackupManager.exportBackup(context, profiles, password, outputStream)`
- Produces: `BackupManager.importBackup(context, password, inputStream)`

- [ ] **Step 1: Add `aes-gcm` dependency to Cargo.toml**

```toml
[dependencies]
jni = "0.21.1"
argon2 = "0.5.3"
aes-gcm = "0.10.3"
rand_core = { version = "0.6", features = ["getrandom"] }
libc = "0.2"
```

- [ ] **Step 2: Add JNI encryption/decryption in `rust_crypto/src/lib.rs`**

```rust
use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};

#[no_mangle]
pub extern "system" fn Java_com_thenile_vault_backup_BackupManager_encryptPayloadNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pass: JString<'local>,
    salt: JString<'local>,
    iv_bytes: jni::objects::JByteArray<'local>,
    plaintext: JString<'local>,
) -> jstring {
    let password: String = env.get_string(&pass).unwrap().into();
    let salt_str: String = env.get_string(&salt).unwrap().into();
    let text: String = env.get_string(&plaintext).unwrap().into();

    let mut key = [0u8; 32];
    argon2::Argon2::default()
        .hash_password_into(password.as_bytes(), salt_str.as_bytes(), &mut key)
        .expect("argon2 key derive failed");

    let iv_vec = env.convert_byte_array(&iv_bytes).unwrap();
    let nonce = Nonce::from_slice(&iv_vec);

    let cipher = Aes256Gcm::new_from_slice(&key).unwrap();
    let ciphertext = cipher.encrypt(nonce, text.as_bytes()).expect("encryption failed");

    let hex_out: String = ciphertext.iter().map(|b| format!("{:02x}", b)).collect();
    env.new_string(hex_out).unwrap().into_raw()
}
```

- [ ] **Step 3: Create `BackupManager.kt` native wrappers & JSON serialization**

```kotlin
package com.thenile.vault.backup

import android.content.Context
import com.thenile.vault.state.Profile
import com.thenile.vault.state.SettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID

object BackupManager {
    init {
        System.loadLibrary("rust_crypto")
    }

    private external fun encryptPayloadNative(pass: String, salt: String, iv: ByteArray, plaintext: String): String
    private external fun decryptPayloadNative(pass: String, salt: String, iv: ByteArray, ciphertextHex: String): String

    fun exportBackup(profiles: List<Profile>, password: String, outputStream: OutputStream): Boolean {
        val jsonArray = JSONArray()
        profiles.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            val pkgs = JSONArray()
            p.packages.forEach { pkgs.put(it) }
            obj.put("packages", pkgs)
            val dirs = JSONArray()
            p.directories.forEach { dirs.put(it) }
            obj.put("directories", dirs)
            obj.put("isActive", p.isActive)
            obj.put("hideOnDecoy", p.hideOnDecoy)
            obj.put("decoyPin", p.decoyPin)
            jsonArray.put(obj)
        }
        val payload = JSONObject().apply {
            put("version", 1)
            put("profiles", jsonArray)
        }.toString()

        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val ivBytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val saltHex = saltBytes.joinToString("") { "%02x".format(it) }

        val ciphertextHex = encryptPayloadNative(password, saltHex, ivBytes, payload)
        val ciphertextBytes = ciphertextHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        outputStream.write("NILE_V1".toByteArray(Charsets.UTF_8))
        outputStream.write(saltBytes)
        outputStream.write(ivBytes)
        outputStream.write(ciphertextBytes)
        outputStream.flush()
        return true
    }
}
```

- [ ] **Step 4: Create unit test `BackupTest.kt`**

```kotlin
package com.thenile.vault

import com.thenile.vault.backup.BackupManager
import com.thenile.vault.state.Profile
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupTest {
    @Test
    fun testBackupExportImportRoundtrip() {
        val testProfiles = listOf(
            Profile("id1", "Work Profile", listOf("com.app"), listOf("/sdcard/doc"), emptyList(), true, true, "1234")
        )
        val out = ByteArrayOutputStream()
        val ok = BackupManager.exportBackup(testProfiles, "SecretPass123", out)
        assertTrue(ok)
        assertTrue(out.toByteArray().size > 35)
    }
}
```

- [ ] **Step 5: Run unit tests via `run_command`**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit Task 1**

```bash
git add app/rust_crypto/ app/src/main/java/com/thenile/vault/backup/ app/src/test/java/com/thenile/vault/BackupTest.kt
git commit -m "feat: implement native Rust AES-256-GCM backup encryption"
```

---

### Task 2: Fake Crash Disguise Screen (`FakeCrashScreen.kt` & `SettingsManager.kt`)

**Files:**
- Modify: `app/src/main/java/com/thenile/vault/state/SettingsManager.kt`
- Create: `app/src/main/java/com/thenile/vault/ui/FakeCrashScreen.kt`

**Interfaces:**
- Consumes: `SettingsManager.enableFakeCrash`
- Produces: `FakeCrashScreen(onBypass: () -> Unit, onExit: () -> Unit)`

- [ ] **Step 1: Add `enableFakeCrash` in `SettingsManager.kt`**

```kotlin
    var enableFakeCrash: Boolean
        get() = prefs.getBoolean("enableFakeCrash", false)
        set(value) { prefs.edit().putBoolean("enableFakeCrash", value).apply() }
```

- [ ] **Step 2: Create `FakeCrashScreen.kt` with 1.5s long-press gesture**

```kotlin
package com.thenile.vault.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.HapticFeedbackType
import androidx.compose.ui.unit.dp

@Composable
fun FakeCrashScreen(onBypass: () -> Unit, onExit: () -> Unit) {
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AlertDialog(
            onDismissRequest = onExit,
            title = { Text("The Nile keeps stopping", style = MaterialTheme.typography.titleMedium) },
            text = { Text("App keeps stopping. Close app or send feedback.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = {},
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onExit() },
                            onLongPress = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onBypass()
                            }
                        )
                    }
                ) {
                    Text("Close app")
                }
            },
            dismissButton = {
                TextButton(onClick = onExit) {
                    Text("Send feedback")
                }
            }
        )
    }
}
```

- [ ] **Step 3: Commit Task 2**

```bash
git add app/src/main/java/com/thenile/vault/state/SettingsManager.kt app/src/main/java/com/thenile/vault/ui/FakeCrashScreen.kt
git commit -m "feat: add Fake Crash Disguise component with long press bypass"
```

---

### Task 3: Admin UI Integration & SAF Launchers in `AdminActivity.kt`

**Files:**
- Modify: `app/src/main/java/com/thenile/vault/ui/AdminActivity.kt`

- [ ] **Step 1: Add Fake Crash toggle and Backup Export/Import buttons to Global Settings tab**

```kotlin
// Fake Crash Toggle
Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.weight(1f)) {
        Text("Fake Crash Disguise", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text("Show fake crash screen on app launch; long press 'Close app' to bypass", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = enableFakeCrash, onCheckedChange = { enableFakeCrash = it })
}

// Backup & Restore Buttons
Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = { showExportPasswordDialog = true }, modifier = Modifier.weight(1f)) {
        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Export Backup")
    }
    OutlinedButton(onClick = { showImportPasswordDialog = true }, modifier = Modifier.weight(1f)) {
        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Import Backup")
    }
}
```

- [ ] **Step 2: Add SAF ActivityResultLaunchers for `.nile` file creation and selection**

```kotlin
val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { stream ->
            BackupManager.exportBackup(settings.profiles, backupPassword, stream)
            Toast.makeText(context, "Backup exported successfully", Toast.LENGTH_SHORT).show()
        }
    }
}

val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let {
        context.contentResolver.openInputStream(it)?.use { stream ->
            val count = BackupManager.importBackup(settings, backupPassword, stream)
            Toast.makeText(context, "Imported $count profiles successfully", Toast.LENGTH_SHORT).show()
        }
    }
}
```

- [ ] **Step 3: Run full build & unit test verification**

Run: `.\gradlew.bat assembleDebug test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Deploy & Verify on connected Android phone `1a1047c2`**

Run: `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 1a1047c2 install -r app/build/outputs/apk/debug/app-debug.apk`
Run: `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 1a1047c2 shell su -c am start -n com.thenile.vault/.ui.AdminActivity`

- [ ] **Step 5: Commit Task 3**

```bash
git add app/src/main/java/com/thenile/vault/ui/AdminActivity.kt
git commit -m "feat: integrate fake crash disguise and encrypted backup SAF UI"
```
