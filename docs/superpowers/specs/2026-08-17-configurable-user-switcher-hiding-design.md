# Design Specification: Configurable User Switcher Hiding in Settings & Notifications

## Overview
Currently, when a Decoy user profile is configured, the user switcher avatar in Quick Settings (notifications pull-down shade) and the "Users" / "Multiple users" entries in Android Settings are hidden by default. This feature introduces two independent user-facing toggles in Decoy Settings so the user can choose whether to hide or keep each switcher interface.

## Components & Architecture

### 1. Settings State (`SettingsManager.kt`)
* Properties:
  * `var hideUserSwitcherInQuickSettings: Boolean` (default: `true`)
  * `var hideUserSwitcherInSettings: Boolean` (default: `true`)
* Synced to `/data/system/thenile_config.json` on changes.

### 2. User Interface (`AdminActivity.kt`)
* In the Decoy Profile section when `decoyLockScreenMode == "switch_user"`:
  * Checkbox 1: `Hide switcher in Quick Settings & Notifications`
    * Subtitle: `Removes the user avatar and profile switcher from the notification panel and Quick Settings shade.`
  * Checkbox 2: `Hide "Users" in Android Settings`
    * Subtitle: `Hides the 'Multiple users' / 'Users' section and profile icon from the system Settings app.`

### 3. Xposed Hook (`PackageManagerHook.kt`)
* Helper methods:
  * `isHideUserSwitcherInQuickSettingsEnabled()`
  * `isHideUserSwitcherInSettingsEnabled()`
* SystemUI hooks: Apply user switcher hiding only when `isHideUserSwitcherInQuickSettingsEnabled()` is true.
* Settings hooks: Apply "Users" preference and avatar hiding only when `isHideUserSwitcherInSettingsEnabled()` is true.

## Testing & Verification Plan
1. Build debug APK `./gradlew assembleDebug`.
2. Install APK on `emulator-5554`.
3. Verify both toggles are visible in Admin UI and synced to `/data/system/thenile_config.json`.
4. Test live UI:
   * Verify Quick Settings shade behavior when toggle is ON vs OFF.
   * Verify Settings > System > Users behavior when toggle is ON vs OFF.
5. Update AST graph with `graphify update .`.
