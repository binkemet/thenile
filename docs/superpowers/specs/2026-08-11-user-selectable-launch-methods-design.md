# Design Specification: User-Selectable Launch Methods for The Nile

**Date:** 2026-08-11  
**Target Package:** `com.thenile.vault`  

---

## 1. Overview
The Nile Stealth Vault Engine provides multiple user-selectable launch methods so users can choose how they open the vault admin interface based on their security, stealth, and convenience preferences.

---

## 2. Supported Launch Methods

### 1. 📞 Secret Dialer Code (Default)
- **Codes:** `*#3333#` (Admin), `*#9876#` (Unlock), `*#1111#` (Lock), `*#1234#` (Decoy).
- **Mechanism:** `SecretCodeReceiver` registered for `android.provider.Telephony.SECRET_CODE`.

### 2. 🧱 Quick Settings Tile (`NileTileService.kt`)
- **Mechanism:** Native Android `TileService` (`com.thenile.vault.services.NileTileService`).
- **Behavior:** Adds a Quick Settings tile ("The Nile") to the notification shade. Tapping it opens `AdminActivity`.

### 3. 🌐 Browser Deep Link (`nile://admin`)
- **Mechanism:** `<intent-filter>` in `AndroidManifest.xml` for `scheme="nile" host="admin"` and `scheme="nile" host="open"`.
- **Behavior:** Typing `nile://admin` in any web browser opens the vault.

### 4. 🔊 Hardware Volume Button Combo (`NileAccessibilityService.kt`)
- **Mechanism:** `AccessibilityService` intercepting `onKeyEvent` for `KEYCODE_VOLUME_DOWN`.
- **Behavior:** Pressing Volume Down twice within 1 second triggers `AdminActivity` launch.

### 5. 🧮 Calculator Decoy Activity (`CalculatorActivity.kt`)
- **Mechanism:** A fully functional calculator UI.
- **Behavior:** Computes standard math operations. Entering `3333=` (or configured Admin PIN) launches `AdminActivity`.

### 6. 🙈 Launcher Icon Hiding
- **Mechanism:** Root `pm hide com.thenile.vault` / `pm unhide com.thenile.vault`.
- **Behavior:** Hides the launcher icon from the app drawer.

---

## 3. Settings UI Integration
In `AdminActivity.kt` (Global Settings tab):
- A new **"Launch & Opening Methods"** Card displaying toggle switches for each opening method.
- Setup instructions and buttons for enabling Accessibility Service (for volume key combo) and Quick Settings Tile.

---

## 4. Verification Plan
1. `./gradlew assembleDebug test` build & unit test verification.
2. ADB installation on device (`adb install -r app-debug.apk`).
3. Functional testing of tile service, deep link, calculator, and volume key listener.
