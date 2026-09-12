# Profile Decoy PIN & Profile Switching Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-profile Decoy PINs and replace the multi-card profile UI with a single profile editor featuring a profile switching modal (with edit/bin icons) and unsaved changes save prompt.

**Architecture:** Update `Profile` data model with `decoyPin`, add code-matching decoy getters in `SettingsManager`, update `PromptActivity` to evaluate per-profile decoy PINs, and redesign `AdminActivity` profile management UI around single-profile editing with a switcher dialog.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Android SharedPreferences, JUnit 4.

## Global Constraints

- Android SDK / Jetpack Compose standard components.
- Standard Material 3 icons (`Icons.Filled.Edit`, `Icons.Filled.Delete`, `Icons.Filled.Add`, etc.).
- Backwards-compatible JSON parsing for legacy `Profile` objects lacking `decoyPin`.

---

### Task 1: Update `Profile` Data Model & Decoy Helpers in `SettingsManager.kt`

**Files:**
- Modify: `app/src/main/java/com/thenile/vault/state/SettingsManager.kt:11-125`
- Create: `app/src/test/java/com/thenile/vault/ProfileDecoyTest.kt`

**Interfaces:**
- Produces:
  - `Profile.decoyPin: String`
  - `SettingsManager.getDecoyPackagesForCode(code: String): List<String>`
  - `SettingsManager.getDecoyDirectoriesForCode(code: String): List<String>`
  - `SettingsManager.getDecoyDummyDirectoriesForCode(code: String): List<DummyDir>`

- [ ] **Step 1: Write unit test for per-profile decoy matching**

Create `app/src/test/java/com/thenile/vault/ProfileDecoyTest.kt`:

```kotlin
package com.thenile.vault

import com.thenile.vault.state.Profile
import com.thenile.vault.state.DummyDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDecoyTest {

    @Test
    fun testProfileDecoyPinMatching() {
        val prof1 = Profile(
            id = "p1",
            name = "Work",
            packages = listOf("com.work.app"),
            directories = listOf("/sdcard/work"),
            dummyDirectories = emptyList(),
            isActive = true,
            hideOnDecoy = true,
            decoyPin = "1234"
        )
        val prof2 = Profile(
            id = "p2",
            name = "Personal",
            packages = listOf("com.personal.app"),
            directories = listOf("/sdcard/personal"),
            dummyDirectories = emptyList(),
            isActive = true,
            hideOnDecoy = true,
            decoyPin = "5678"
        )

        assertEquals("1234", prof1.decoyPin)
        assertEquals("5678", prof2.decoyPin)
    }
}
```

- [ ] **Step 2: Run unit test to verify it compiles/runs**

Run: `./gradlew test`
Expected: PASS or compile error if `decoyPin` not yet on `Profile`.

- [ ] **Step 3: Implement `decoyPin` on `Profile` and helper methods in `SettingsManager.kt`**

Update `Profile` data class in `SettingsManager.kt`:

```kotlin
data class Profile(
    val id: String,
    var name: String,
    var packages: List<String>,
    var directories: List<String>,
    var dummyDirectories: List<DummyDir>,
    var isActive: Boolean,
    var hideOnDecoy: Boolean = true,
    var decoyPin: String = ""
)
```

Update JSON reading/writing in `SettingsManager`:
- In `profiles` getter: read `obj.optString("decoyPin", if (obj.optBoolean("hideOnDecoy", false)) "1234" else "")`
- In `profiles` setter: put `"decoyPin", p.decoyPin` into `obj`

Add code-specific decoy getters in `SettingsManager`:

```kotlin
fun getDecoyPackagesForCode(code: String): List<String> {
    return profiles.filter { it.decoyPin == code || (code == codeDecoy && it.decoyPin.isNotBlank()) }
        .flatMap { it.packages }.distinct()
}

fun getDecoyDirectoriesForCode(code: String): List<String> {
    return profiles.filter { it.decoyPin == code || (code == codeDecoy && it.decoyPin.isNotBlank()) }
        .flatMap { it.directories }.distinct()
}

fun getDecoyDummyDirectoriesForCode(code: String): List<DummyDir> {
    return profiles.filter { it.decoyPin == code || (code == codeDecoy && it.decoyPin.isNotBlank()) }
        .flatMap { it.dummyDirectories }.distinct()
}
```

- [ ] **Step 4: Run unit test to verify pass**

Run: `./gradlew test`
Expected: PASS

---

### Task 2: Update Decoy PIN Execution in `PromptActivity.kt`

**Files:**
- Modify: `app/src/main/java/com/thenile/vault/ui/PromptActivity.kt:118-126`

**Interfaces:**
- Consumes: `SettingsManager.getDecoyPackagesForCode(code)`, `SettingsManager.getDecoyDirectoriesForCode(code)`, `SettingsManager.getDecoyDummyDirectoriesForCode(code)`

- [ ] **Step 1: Update decoy code handling in `PromptActivity.kt`**

In `PromptActivity.kt`:
Update `handleSuccess(code: String)` branch for decoy:

```kotlin
val isProfileDecoy = code == settings.codeDecoy || settings.profiles.any { it.decoyPin.isNotBlank() && it.decoyPin == code }
```

In `when (code)` (or checking `isProfileDecoy`):

```kotlin
isProfileDecoy -> {
    stateManager.updateState(VaultState.DECOY)
    val decoyTargets = settings.getDecoyPackagesForCode(code)
    val decoyDirs = settings.getDecoyDirectoriesForCode(code)
    val decoyDummy = settings.getDecoyDummyDirectoriesForCode(code)
    val ok = com.thenile.vault.root.StorageMountManager.mountDecoyDirectory(decoyTargets, decoyDirs, decoyDummy)
    decoyTargets.forEach { pkg -> com.thenile.vault.root.TraceCleaner.cleanTraces(pkg) }
    if (ok) "Decoy active" else "Decoy state set, but mount failed (see logs)"
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 3: Overhaul Profile UI in `AdminActivity.kt`

**Files:**
- Modify: `app/src/main/java/com/thenile/vault/ui/AdminActivity.kt`

**Interfaces:**
- Consumes: `SettingsManager.profiles`, `Profile.decoyPin`
- Produces: `ProfileListDialog` composable, single profile editor UI, unsaved changes confirmation dialog (`SavePromptDialog`)

- [ ] **Step 1: Add `ProfileListDialog` composable**

Add to `AdminActivity.kt`:

```kotlin
@Composable
fun ProfileListDialog(
    profiles: List<Profile>,
    currentProfileId: String,
    onSelectProfile: (Profile) -> Unit,
    onDeleteProfile: (Profile) -> Unit,
    onDismiss: () -> Unit
) {
    var profileToDelete by remember { mutableStateOf<Profile?>(null) }

    if (profileToDelete != null) {
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Delete Profile") },
            text = { Text("Are you sure you want to delete profile '${profileToDelete?.name}'?") },
            confirmButton = {
                TextButton(onClick = {
                    profileToDelete?.let { onDeleteProfile(it) }
                    profileToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch Profile") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(profiles) { profile ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = if (profile.id == currentProfileId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name, fontWeight = FontWeight.Bold)
                                Text(
                                    if (profile.isActive) "Active (Hidden)" else "Inactive",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = { onSelectProfile(profile) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit Profile", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { profileToDelete = profile }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete Profile", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
```

- [ ] **Step 2: Add `UnsavedChangesDialog` composable**

```kotlin
@Composable
fun UnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unsaved Changes") },
        text = { Text("You have unsaved changes in your profile. Do you want to save them before proceeding?") },
        confirmButton = {
            TextButton(onClick = onSave) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}
```

- [ ] **Step 3: Update `AdminScreen` profile section to single profile editor with state tracking**

In `AdminScreen`:
- Maintain state:
  - `var selectedProfileId by remember { mutableStateOf(profiles.firstOrNull()?.id ?: "") }`
  - `var editingProfileState by remember(selectedProfileId) { mutableStateOf(profiles.find { it.id == selectedProfileId } ?: createEmptyProfile()) }`
  - `var originalProfileState by remember(selectedProfileId) { mutableStateOf(editingProfileState.copy()) }`
  - `val isDirty = editingProfileState != originalProfileState`
  - `var showProfileListModal by remember { mutableStateOf(false) }`
  - `var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }` // Action to perform after save/discard confirmation

If `pendingAction != null`: show `UnsavedChangesDialog`.
- `onSave`: Save profile to `profiles`, update `settings.profiles = profiles`, reset `isDirty`, execute `pendingAction()`, clear `pendingAction`.
- `onDiscard`: Revert changes, execute `pendingAction()`, clear `pendingAction`.
- `onCancel`: Clear `pendingAction`.

In Profile tab UI:
- **Top Row**:
  - Header showing current profile name.
  - "Switch Profile" button:
    - If `isDirty`: set `pendingAction = { showProfileListModal = true }`
    - Else: `showProfileListModal = true`
  - "Add Profile" button:
    - If `isDirty`: set `pendingAction = { createNewProfileAndSelect() }`
    - Else: `createNewProfileAndSelect()`
- **Profile Editor**:
  - Profile Name field (`editingProfileState.name`)
  - Decoy PIN field (`editingProfileState.decoyPin`)
  - Active toggle switch
  - Hide / Unhide buttons
  - Apps picker section
  - Directories picker section
  - Dummy folders section
  - "Save Profile" button: saves `editingProfileState` to `profiles`, calls `settings.profiles = profiles`, updates `originalProfileState = editingProfileState.copy()`, shows Toast `"Profile saved"`.

- [ ] **Step 4: Verify build with `./gradlew assembleDebug`**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run unit tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL & tests pass.
