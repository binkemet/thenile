# User-Configurable User Switching Animation Suppression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to toggle whether the full-screen "Switching to Decoy..." animation dialog is shown or suppressed when switching profiles.

**Architecture:** Add `suppressUserSwitchAnimation` to `SettingsManager` (synced to `/data/system/thenile_config.json`), expose a toggle in `AdminActivity.kt` Decoy settings, and hook `com.android.server.am.UserController.isUserSwitchUiEnabled()` + `showUserSwitchDialog` and `com.android.server.am.UserSwitchingDialog` in `PackageManagerHook.kt`.

**Tech Stack:** Kotlin, Jetpack Compose, LSPosed/Vector Xposed API, Android Multi-User System Server APIs.

## Global Constraints
- Must be enabled (`true`) by default to keep decoy switching stealthy.
- Must persist across reboots and sync to `/data/system/thenile_config.json`.
- Must support live toggling in AdminActivity.

---

### Task 1: Add `suppressUserSwitchAnimation` to SettingsManager
**Files:**
- Modify: `D:\thenile\app\src\main\java\com\thenile\vault\state\SettingsManager.kt`

- [ ] **Step 1: Add property & sync in SettingsManager.kt**
```kotlin
var suppressUserSwitchAnimation: Boolean
    get() = prefs.getBoolean("suppressUserSwitchAnimation", true)
    set(value) { prefs.edit().putBoolean("suppressUserSwitchAnimation", value).commit(); syncToSystem() }
```
Add to `syncToSystem()` JSON output:
```kotlin
json.put("suppressUserSwitchAnimation", suppressUserSwitchAnimation)
```

---

### Task 2: Add Toggle in AdminActivity UI
**Files:**
- Modify: `D:\thenile\app\src\main\java\com\thenile\vault\ui\AdminActivity.kt`

- [ ] **Step 1: Add state and Switch / Checkbox in Decoy settings**
In `AdminActivity.kt`:
```kotlin
var suppressUserSwitchAnimation by remember { mutableStateOf(settings.suppressUserSwitchAnimation) }
```
In the `decoyLockScreenMode == "switch_user"` UI block:
```kotlin
Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { suppressUserSwitchAnimation = !suppressUserSwitchAnimation }) {
    Checkbox(checked = suppressUserSwitchAnimation, onCheckedChange = { suppressUserSwitchAnimation = it })
    Spacer(modifier = Modifier.width(8.dp))
    Column {
        Text("Hide switching animation", style = MaterialTheme.typography.bodyMedium)
        Text("Silently switch profiles without displaying the full-screen 'Switching to Decoy...' dialog", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```
And in save block:
```kotlin
settings.suppressUserSwitchAnimation = suppressUserSwitchAnimation
```

---

### Task 3: Hook UserController & UserSwitchingDialog in PackageManagerHook
**Files:**
- Modify: `D:\thenile\app\src\main\java\com\thenile\vault\xposed\PackageManagerHook.kt`

- [ ] **Step 1: Hook UserController.isUserSwitchUiEnabled and showUserSwitchDialog**
In `PackageManagerHook.kt`:
Read `suppressUserSwitchAnimation` (default true) from `/data/system/thenile_config.json`.
Hook `com.android.server.am.UserController`:
- `isUserSwitchUiEnabled()` $\rightarrow$ return `false` if `suppressUserSwitchAnimation` is true.
- `showUserSwitchDialog` $\rightarrow$ return `null` / do nothing.
- `showUserSwitchingDialog` $\rightarrow$ return `null` / do nothing.
- `com.android.server.am.UserSwitchingDialog` $\rightarrow$ suppress `show()`.

---

### Task 4: Build, Install, and Live Verification
**Files:**
- Build APK: `./gradlew assembleDebug`
- Install: `adb install -r ...`

- [ ] **Step 1: Build debug APK**
- [ ] **Step 2: Install APK & restart system_server / dialer**
- [ ] **Step 3: Test profile switch with toggle ON $\rightarrow$ verify 0s delay and NO dialog on screen**
- [ ] **Step 4: Run `graphify update .`**
