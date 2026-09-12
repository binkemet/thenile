# Design Document: Per-Profile Decoy PIN & Profile Switching Overhaul

**Date:** 2026-08-11  
**Target Project:** The Nile Vault (`com.thenile.vault`)  

---

## 1. Objectives

1. **Per-Profile Decoy PIN**: Allow each profile to define its own `decoyPin`. Entering a profile's specific Decoy PIN hides that profile's apps, directories, and dummy folders instead of relying solely on a global decoy switch.
2. **Streamlined Profile Creation**: Prevent clicking "Add Profile" from spawning an additional full-card form in a growing list. Require checking for unsaved profile changes ("if there is something in it") and asking the user if they want to save before creating a new profile.
3. **Profile Switcher UI**: Replace the multi-card stack with a single profile editor and a "Switch Profile" button that opens a profile list modal. Each entry in the modal displays an **Edit (pencil) icon** and a **Bin (trash) icon**.

---

## 2. Architecture & Data Model Changes

### 2.1 `Profile` Model Update (`SettingsManager.kt`)
Add `decoyPin: String` to the `Profile` data class:

```kotlin
data class Profile(
    val id: String,
    var name: String,
    var packages: List<String>,
    var directories: List<String>,
    var dummyDirectories: List<DummyDir>,
    var isActive: Boolean,
    var hideOnDecoy: Boolean = true,
    var decoyPin: String = "" // New field per profile
)
```

- JSON serialization in `SettingsManager` reads and writes `decoyPin`.
- For backward compatibility, if `decoyPin` is not present in JSON, default it to `"1234"` if `hideOnDecoy` is true, or `""` if false.

### 2.2 Decoy Matching Logic (`SettingsManager.kt` & `PromptActivity.kt`)
- In `SettingsManager`:
  - `getDecoyPackagesForCode(code: String)`: Returns distinct packages for profiles matching `profile.decoyPin == code` (or all profiles with non-empty `decoyPin` if `code == globalCodeDecoy`).
  - `getDecoyDirectoriesForCode(code: String)`: Returns distinct directories for matching profiles.
  - `getDecoyDummyDirectoriesForCode(code: String)`: Returns distinct dummy directories for matching profiles.
- In `PromptActivity.kt`:
  - When secret dial code or PIN entry matches any profile's `decoyPin` or global `codeDecoy`:
    - Resolve the targeted packages, directories, and dummy directories for that specific code.
    - Trigger `mountDecoyDirectory(...)` and `cleanTraces(...)` for the target profiles.

---

## 3. UI/UX Redesign (`AdminActivity.kt`)

### 3.1 Single Profile View & Editing State
Instead of displaying all profiles on a single scrolling page, `AdminScreen` will manage:
- `currentProfile`: The single `Profile` being edited.
- `originalProfile`: A copy of the profile when opened, used to detect unsaved changes (`isDirty = currentProfile != originalProfile`).
- `showSavePrompt`: Dialog shown when attempting to switch/add profiles while `isDirty` is true.
- `showProfileListDialog`: Dialog showing all saved profiles.

### 3.2 Profile Header & Switcher Button
At the top of the Profiles tab:
1. **Header**: Shows `Editing: <Profile Name>`.
2. **"Switch Profile" Button**:
   - Triggers `isDirty` check. If dirty, opens save confirmation prompt ("Save changes to current profile before switching?"). Options: "Save & Switch", "Discard & Switch", "Cancel".
   - If clean (or after save/discard decision), opens `ProfileListDialog`.
3. **"Add New Profile" Button**:
   - Triggers `isDirty` check. If dirty, opens save confirmation prompt.
   - If clean (or after save/discard decision), opens a blank `Profile` in the editor.

### 3.3 Profile List Dialog (`ProfileListDialog`)
A modal dialog listing all existing profiles:
- Each row contains:
  - **Profile Name** and **Status** (Active / Hidden badge).
  - **Edit Icon (Pencil)**: Loads this profile into the editor and dismisses dialog.
  - **Bin Icon (Trash)**: Shows confirmation prompt ("Delete profile '<Name>'?"), then removes the profile upon confirmation.

### 3.4 Profile Editor Components
The main profile editor renders controls for the currently selected profile:
- **Profile Name Field**: `OutlinedTextField`
- **Decoy PIN Field**: `OutlinedTextField` for setting THIS profile's Decoy PIN.
- **Active (Hidden) Switch**: Toggle active status.
- **Hide / Unhide Buttons**: Perform container lock/unlock operations for this profile.
- **Hidden Apps Section**: App picker launcher & list with remove `X` buttons.
- **Hidden Directories Section**: Directory picker launcher & list with remove `X` buttons.
- **Dummy Folders Section**: Add dummy folder dialog launcher & list with remove `X` buttons.
- **Save Profile Button**: Persists the changes to `SettingsManager` and resets `isDirty`.

---

## 4. Verification Plan

1. **Build Verification**:
   - Run `./gradlew assembleDebug` via `run_command` to ensure zero compilation errors.
2. **Unit / Logic Verification**:
   - Verify `SettingsManager` properly serializes and deserializes `decoyPin` for profiles.
   - Verify `getDecoyPackagesForCode` returns only packages for the profile matching the given decoy PIN.
