# Implementation Plan: Animations & Performance Optimization

**Design Spec:** [`docs/superpowers/specs/2026-08-11-animations-and-performance-optimization-design.md`](file:///D:/thenile/docs/superpowers/specs/2026-08-11-animations-and-performance-optimization-design.md)  
**Target Package:** `com.thenile.vault`  

---

## Tasks

### Task 1: In-Memory JSON & Settings Caching
- Update `SettingsManager.kt`:
  - Cache parsed `List<Profile>` in an in-memory variable `_cachedProfiles`.
  - Invalidate cache when profiles are updated or set.
  - Optimize `syncToSystem()` execution to run efficiently in background.
- Verify unit tests pass (`./gradlew test`).

### Task 2: Expressive Compose Motion & Micro-Animations
- Update `AdminActivity.kt`:
  - Replace tab swapping `if (currentTab == 0)` with `AnimatedContent` using `slideInHorizontally` + `fadeIn` and `slideOutHorizontally` + `fadeOut`.
  - Wrap conditional text fields and action buttons in `AnimatedVisibility(visible = ...)` with `expandVertically` + `fadeIn`.
  - Animate profile item selection colors with `animateColorAsState`.
  - Animate floating navigation bar chip indicator selection states.

### Task 3: Compose Recomposition & Shell Threading Optimization
- Update `AdminActivity.kt`:
  - Wrap `isDirty` calculation in `remember(editingProfileState, originalProfileState) { derivedStateOf { editingProfileState != originalProfileState } }`.
  - Add explicit `key = { it.id }` in `items(profiles)` in `ProfileListDialog`.
  - Dispatch root operations (`Shell.cmd`) to `Dispatchers.IO`.

### Task 4: Full Verification, ADB Installation & Performance Test
- Run `./gradlew.bat assembleDebug test`.
- Install onto physical device (`1a1047c2`) via ADB (`adb install -r app-debug.apk`).
- Launch `AdminActivity` as root and verify smooth animations and instantaneous responsiveness.
