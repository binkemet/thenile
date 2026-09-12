# Design Specification: Material You Expressive UI & Ergonomic Redesign

## Goal
Transform The Nile Vault Admin UI into a modern, ergonomic Google Material You Expressive application with intuitive navigation, zero UI overlapping/blocking, structured categorization, and native Material 3 controls.

## Key Ergonomic & UI Improvements

### 1. Docked Material 3 NavigationBar
* **Problem**: The existing floating capsule hovered directly over interactive list elements, blocking text fields, checkboxes, and buttons during scrolling.
* **Solution**: Replace with a native `Scaffold(bottomBar = { NavigationBar { ... } })` with expressive pill indicators, labels, and auto-handled content insets.

### 2. Streamlined Profiles Tab
* **Hero Status Card**: Expressive container displaying profile status, quick profile switcher menu, and large primary action buttons (Lock/Hide and Unlock/Unhide).
* **Card Groups**:
  * Profile Identity & Decoy PIN.
  * Hidden Applications with count badge and modern list tiles.
  * Hidden Folders & Dummy Mappings with path chips.
* **Floating Save Action**: Extended FAB / Primary button that emphasizes unsaved changes.

### 3. Categorized Settings Tab
* **Category Filter Header**: Quick-scroll / category selector (`All`, `Decoy`, `Triggers`, `Security`, `Backup`).
* **Material 3 Preference Rows**: Replace raw checkboxes with full-width clickable list tiles featuring `Switch` components, clear title typography, and supporting descriptions.
* **Segmented Mode Pickers**: Replace raw radio buttons with clean selectable cards or SegmentedButtons for decoy lock modes and authentication methods.

### 4. Visual Excellence & Tokens
* Utilize dynamic M3 color tokens (`surfaceContainer`, `surfaceContainerLow`, `surfaceContainerHigh`, `primaryContainer`, `onPrimaryContainer`).
* Consistent 24dp-28dp corner radii on cards, 16dp generous content padding, and minimum 48dp touch targets for optimal single-handed ergonomics.

## Verification
* `./gradlew assembleDebug`
* Install and verify on `emulator-5554`.
* Verify both Profiles and Settings tabs with screencap inspections.
* Synchronize AST graph with `graphify update .`.
