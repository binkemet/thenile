# Fake Crash Disguise & Encrypted Backup Design Specification

## Overview
This specification details two key features for **The Nile Stealth Engine**:
1. **Fake Crash Disguise**: A stealth entry barrier that displays a pixel-perfect fake Android system crash dialog (*"The Nile keeps stopping"*). Bypassed only by long-pressing `"Close app"` for 1.5 seconds.
2. **Encrypted Profile Backup & Restore**: Secure export and import of profiles, hidden app packages, target directories, and dummy folder mappings using **AES-256-GCM** encryption with PBKDF2 key derivation.

---

## 1. Fake Crash Disguise

### 1.1 Configuration State
- Field added to `SettingsManager`:
  - `var enableFakeCrash: Boolean` (stored in `SharedPreferences`, default `false`).
- Toggle UI added to **Global Settings** tab in `AdminActivity.kt`.

### 1.2 User Interface & Gesture (`FakeCrashScreen.kt`)
- When `enableFakeCrash == true` and `AdminActivity` or `PromptActivity` is launched directly:
  - Renders `FakeCrashScreen` before authentication.
  - Dialog Title: *"The Nile keeps stopping"* (or system app label).
  - Message: *"App keeps stopping. Close app or send feedback."*
  - Buttons:
    - `"Close app"`:
      - Single tap: Exits app (`activity.finish()`).
      - Long press (>= 1,500ms): Triggers `HapticFeedbackType.LongPress` vibration and transitions to Master authentication screen.
    - `"Send feedback"`:
      - Single tap: Shows toast *"Feedback service unavailable"* or closes app.

---

## 2. Encrypted Backup & Restore

### 2.1 Encryption & Binary File Specification (`rust_crypto` / `BackupManager.kt`)
- **Engine**: Built directly into native Rust C-dylib (`app/rust_crypto`) exposed via JNI to Kotlin `BackupManager`.
- **Key Derivation**: Argon2id via Rust `argon2` crate with 16-byte random salt, generating a 256-bit secret key.
- **Cipher**: `aes-gcm` Rust crate (AES-256-GCM) with a 12-byte random initialization vector (IV) and 128-bit authentication tag.
- **File Format (`.nile`)**:
  - `[0..6]`: Magic Header ASCII `NILE_V1` (7 bytes)
  - `[7..22]`: Salt (16 bytes)
  - `[23..34]`: IV (12 bytes)
  - `[35..EOF]`: Ciphertext (AES-256-GCM encrypted JSON payload)

### 2.2 Payload Structure (JSON)
```json
{
  "version": 1,
  "timestamp": 1786423000000,
  "profiles": [
    {
      "id": "uuid-1",
      "name": "Default Profile",
      "packages": ["com.example.hidden"],
      "directories": ["/sdcard/Secret"],
      "dummyDirectories": [
        { "target": "/sdcard/Secret", "dummy": "/sdcard/Dummy", "encrypt": false }
      ],
      "isActive": true,
      "hideOnDecoy": true,
      "decoyPin": "1234"
    }
  ]
}
```

### 2.3 Storage Access Framework (SAF) Workflow
- **Export**:
  - User clicks `"Export Backup"` in Global Settings.
  - Password Dialog prompts for Master Backup Password.
  - SAF `CreateDocument` launcher opens (`nile_backup_YYYYMMDD.nile`).
  - `BackupManager.exportBackup(...)` writes encrypted payload to stream.
  - Toast displayed: *"Backup exported successfully"*.
- **Import**:
  - User clicks `"Import Backup"` in Global Settings.
  - SAF `OpenDocument` launcher opens to pick `.nile` file.
  - Password Dialog prompts for Backup Password.
  - `BackupManager.importBackup(...)` decrypts payload.
  - Duplicates checked: imported profiles merged into `SettingsManager.profiles` with new UUIDs if conflicts exist.
  - Toast displayed: *"Imported N profiles successfully"*.

---

## 3. Verification & Self-Review
- Unit tests in `BackupTest.kt` verifying PBKDF2 AES-256-GCM encryption, decryption, incorrect password rejection, and payload parsing.
- UI build verification with `./gradlew assembleDebug test`.
- ADB installation and manual verification on device `1a1047c2`.
