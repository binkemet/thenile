# Configurable User Switcher Hiding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow the user to independently choose whether to hide the user switcher in Quick Settings/Notifications and/or Android Settings.

**Architecture:** Add `hideUserSwitcherInQuickSettings` and `hideUserSwitcherInSettings` to `SettingsManager` (synced to JSON config), expose both toggles in `AdminActivity.kt`, and guard the respective Xposed hooks in `PackageManagerHook.kt`.

**Tech Stack:** Kotlin, Jetpack Compose, LSPosed/Vector Xposed API.

## Global Constraints
- Both options default to `true` to maintain stealth.
- Must sync to `/data/system/thenile_config.json`.
- Live configurable in Admin UI.

---

### Task 1: Update SettingsManager
**Files:**
- Modify: `D:\thenile\app\src\main\java\com\thenile\vault\state\SettingsManager.kt`

- [ ] **Step 1: Add properties & sync in SettingsManager.kt**
Add:
```kotlin
var hideUserSwitcherInQuickSettings: Boolean
    get() = prefs.getBoolean("hideUserSwitcherInQuickSettings", true)
    set(value) { prefs.edit().putBoolean("hideUserSwitcherInQuickSettings", value).commit(); syncToSystem() }

var hideUserSwitcherInSettings: Boolean
    get() = prefs.getBoolean("hideUserSwitcherInSettings", true)
    set(value) { prefs.edit().putBoolean("hideUserSwitcherInSettings", value).commit(); syncToSystem() }
```
Add to `syncToSystem()`:
```kotlin
json.put("hideUserSwitcherInQuickSettings", hideUserSwitcherInQuickSettings)
json.put("hideUserSwitcherInSettings", hideUserSwitcherInSettings)
```

---

### Task 2: Update AdminActivity UI
**Files:**
- Modify: `D:\thenile\app\src\main\java\com\thenile\vault\ui\AdminActivity.kt`

- [ ] **Step 1: Add state variables and Checkboxes in Decoy Settings**
In `AdminActivity.kt`:
```kotlin
var hideUserSwitcherInQuickSettings by remember { mutableStateOf(settings.hideUserSwitcherInQuickSettings) }
var hideUserSwitcherInSettings by remember { mutableStateOf(settings.hideUserSwitcherInSettings) }
```
Add UI rows for both options under `decoyLockScreenMode == "switch_user"`.
Add save assignments in the Save button onClick handler.

---

### Task 3: Update PackageManagerHook
**Files:**
- Modify: `D:\thenile\app\src\main\java\com\thenile\vault\xposed\PackageManagerHook.kt`

- [ ] **Step 1: Guard SystemUI hooks with isHideUserSwitcherInQuickSettingsEnabled()**
- [ ] **Step 2: Guard Settings hooks with isHideUserSwitcherInSettingsEnabled()**

---

### Task 4: Build, Install, and Verify
**Files:**
- Build APK: `./gradlew assembleDebug`
- Install: `adb install -r ...`

- [ ] **Step 1: Build debug APK**
- [ ] **Step 2: Install APK on emulator-5554**
- [ ] **Step 3: Verify Admin UI settings and /data/system/thenile_config.json**
- [ ] **Step 4: Run `graphify update .`**
