# Implementation Plan: User-Selectable Launch Methods

**Design Spec:** [`docs/superpowers/specs/2026-08-11-user-selectable-launch-methods-design.md`](file:///D:/thenile/docs/superpowers/specs/2026-08-11-user-selectable-launch-methods-design.md)  
**Target Package:** `com.thenile.vault`  

---

## Tasks

### Task 1: Quick Settings Tile & Deep Link Integration
1. Create `app/src/main/java/com/thenile/vault/services/NileTileService.kt`:
   - Extend `TileService`.
   - In `onClick()`: launch `AdminActivity` as root via `Shell.cmd("am start -n com.thenile.vault/.ui.AdminActivity").exec()` or `startActivityAndCollapse()`.
2. Update `app/src/main/AndroidManifest.xml`:
   - Declare `NileTileService` with `permission="android.permission.BIND_QUICK_SETTINGS_TILE"`.
   - Add `<intent-filter>` to `PromptActivity` / `AdminActivity` for `scheme="nile" host="admin"` and `scheme="nile" host="open"`.
3. Verify build with `./gradlew assembleDebug`.

### Task 2: Hardware Volume Buttons & Decoy Calculator
1. Create `app/src/main/java/com/thenile/vault/services/NileAccessibilityService.kt`:
   - Extend `AccessibilityService`.
   - Intercept `onKeyEvent(event)` for `KEYCODE_VOLUME_DOWN`.
   - Detect double-tap within 1000ms and launch `AdminActivity`.
2. Create `app/src/main/java/com/thenile/vault/ui/CalculatorActivity.kt`:
   - A Compose calculator UI supporting basic operations (`+`, `-`, `*`, `/`, `=`).
   - Typing `3333=` (or admin PIN) launches `AdminActivity`.
3. Update `app/src/main/AndroidManifest.xml` to declare `NileAccessibilityService` and `CalculatorActivity`.
4. Verify build with `./gradlew assembleDebug`.

### Task 3: Settings UI Overhaul & Toggles
1. Update `SettingsManager.kt`:
   - Add preferences `enableTile: Boolean`, `enableDeepLink: Boolean`, `enableVolumeKeys: Boolean`, `enableCalculatorDecoy: Boolean`.
2. Update `AdminActivity.kt`:
   - Add a new **"Launch & Opening Methods"** Card under Global Settings tab (`currentTab == 1`).
   - Add toggle switches for each method with instructions and action buttons (e.g. "Enable Accessibility Service").
3. Verify build with `./gradlew assembleDebug test`.

### Task 4: Integration, Build & Phone Deployment
1. Build final APK (`./gradlew assembleDebug test`).
2. Deploy to phone via ADB (`adb install -r app/build/outputs/apk/debug/app-debug.apk`).
3. Update knowledge graph (`graphify update .`).
