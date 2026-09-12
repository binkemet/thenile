# Design Specification: User-Configurable User Switching Animation Suppression

## Overview
When switching between the Owner profile (User 0) and the Decoy profile (User 10), Android displays a full-screen system dialog with an avatar and text ("Switching to Decoy..."). This design adds a user-facing toggle in the Decoy Profile settings to let the user enable or disable this animation, and hooks Android's `UserController` and `UserSwitchingDialog` to completely suppress the UI dialog when enabled.

## Components & Architecture

### 1. Settings State (`SettingsManager.kt`)
* Property: `var suppressUserSwitchAnimation: Boolean`
  * Default: `true` (animation hidden by default for maximum stealth).
  * Backed by SharedPreferences: `"suppressUserSwitchAnimation"`.
  * Synced to `/data/system/thenile_config.json` on change.

### 2. User Interface (`AdminActivity.kt`)
* Under the Decoy Profile section when `decoyLockScreenMode == "switch_user"`:
  * Add a Switch/Checkbox row:
    * Label: `Hide "Switching profile..." animation`
    * Subtitle: `Silently and instantly switches profiles without displaying the full-screen user switching dialog or avatar animation.`
    * Checked state bound to `suppressUserSwitchAnimation`.

### 3. Xposed Hook (`PackageManagerHook.kt`)
* In `hookSystemServer(cl: ClassLoader)`:
  * Hook `com.android.server.am.UserController`:
    * `isUserSwitchUiEnabled()`: Intercept to return `false` if `suppressUserSwitchAnimation` is true in config, cleanly telling Android's user switch controller not to construct or show the user switch UI.
    * `showUserSwitchDialog`: Intercept to return `null` / do nothing if enabled.
  * Hook `com.android.server.am.UserSwitchingDialog`:
    * `show()` and constructors: Intercept and suppress dialog display if enabled.

## Testing & Verification Plan
1. Build debug APK `./gradlew assembleDebug`.
2. Install APK on `emulator-5554`.
3. Verify toggle state in Admin UI and `/data/system/thenile_config.json`.
4. Trigger profile switch (PIN / dialer `*#*#1234#*#*` / calculator `1234=`):
   * With toggle ON: Verify no dialog/animation appears and user switches silently.
   * With toggle OFF: Verify standard Android switching dialog appears.
5. Update AST graph with `graphify update .`.
