# Design Specification: Smooth Animations & Ultra Performance Optimization

**Goal Directive:** Add smooth beautiful animations & optimize app speed to maximum efficiency.  
**Date:** 2026-08-11  
**Target Package:** `com.thenile.vault`  

---

## 1. Smooth Animations & Motion Architecture

### 1.1 Tab & Screen Transitions
- **`AnimatedContent`**:
  - Tab switching between *Profiles* (tab 0) and *Settings* (tab 1) using directional slide-fade transitions:
    - Forward (0 -> 1): `slideInHorizontally { width -> width } + fadeIn()` vs `slideOutHorizontally { width -> -width } + fadeOut()`.
    - Backward (1 -> 0): `slideInHorizontally { width -> -width } + fadeIn()` vs `slideOutHorizontally { width -> width } + fadeOut()`.

### 1.2 Card & Item Micro-Animations
- **Elevation & Color Motion (`animateDpAsState`, `animateColorAsState`)**:
  - Profile selection surface background transitions smoothly between `surfaceContainerLow` and `primaryContainer`.
  - Status badge chip animates container color between active (`primaryContainer`) and inactive (`errorContainer`).
  - Smooth spring physics (`spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)`) for buttons and cards.

### 1.3 Animated Visibility for Dynamic UI Components
- **`AnimatedVisibility`**:
  - Custom App PIN text field smoothly expands/collapses when selecting *Custom App PIN*.
  - Auxiliary settings buttons (e.g. Accessibility Service setup button, Decoy Calculator preview button) expand smoothly when toggled ON.

---

## 2. Performance & Speed Optimization

### 2.1 Recomposition & Rendering Efficiency
- **LazyColumn Keying**:
  - Add explicit `key = { profile.id }` in `ProfileListDialog` and item lists.
- **`derivedStateOf` for Dirty Checking**:
  - Replace raw equality check `editingProfileState != originalProfileState` with `remember(editingProfileState, originalProfileState) { derivedStateOf { editingProfileState != originalProfileState } }`.
- **Lambda Memory Allocation Reduction**:
  - Stabilize callbacks passed into Compose components to prevent unnecessary redraws.

### 2.2 Shell Command & Threading Performance
- **Asynchronous Non-Blocking Shell Operations**:
  - Move disk and root operations (`Shell.cmd(...)`) off main thread via `Dispatchers.IO` / background threads.
  - Avoid redundant `Shell.cmd()` executions during state reads.

### 2.3 JSON & Preference Memory Caching
- **Cached Settings**:
  - In `SettingsManager`, cache parsed `Profile` lists in memory to avoid repeated JSON parsing on every read.

---

## 3. Verification & Benchmark Plan
1. Compile and run unit tests (`./gradlew.bat assembleDebug test`).
2. ADB install onto device (`1a1047c2`).
3. Empirical performance check (FPS, UI responsiveness, launch speed).
