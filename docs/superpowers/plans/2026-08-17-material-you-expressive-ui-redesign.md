# Material You Expressive UI & Ergonomic Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign `AdminActivity.kt` to follow Google Material You Expressive standards, fix all ergonomic overlap/clutter issues, and use modern Material 3 components.

**Architecture:** Refactor `AdminActivity.kt` into clean composables (`ProfilesScreen`, `SettingsScreen`, `PreferenceSwitchRow`, `SelectableOptionCard`, `SectionHeaderCard`), using `Scaffold(bottomBar = ...)` for native bottom navigation.

**Tech Stack:** Jetpack Compose, Material 3, Dynamic Color / Material You.

---

### Task 1: Create Expressive UI Components & Helper Composables
**Files:**
- Modify: `D:\thenile\app\src\main\java\com\thenile\vault\ui\AdminActivity.kt`

- [ ] **Step 1: Implement `PreferenceSwitchRow`**
A full-width, ergonomic clickable row with icon, title, description, and Material 3 `Switch`.

- [ ] **Step 2: Implement `SelectableOptionCard`**
An expressive card for single-choice selections (e.g. Decoy mode, Admin Lock method) with active container coloring and indicator icon.

- [ ] **Step 3: Implement `SectionHeaderCard`**
An expressive header card with icon badge, title, and optional chip.

---

### Task 2: Refactor Navigation & Scaffold
**Files:**
- Modify: `D:\thenile\app\src\main\java\com\thenile\vault\ui\AdminActivity.kt`

- [ ] **Step 1: Replace floating pill with native `NavigationBar` in `Scaffold(bottomBar = ...)`**
Use `NavigationBarItem` with `NavigationBarDefaults` and expressive M3 icons and labels ("Profiles", "Settings").

---

### Task 3: Redesign Profiles Tab
**Files:**
- Modify: `D:\thenile\app\src\main\java\com\thenile\vault\ui\AdminActivity.kt`

- [ ] **Step 1: Build Hero Status & Switcher Card**
- [ ] **Step 2: Build Profile Identity Card (Name, Decoy PIN, Active Switch)**
- [ ] **Step 3: Build Hidden Apps Card with modern tiles and badge**
- [ ] **Step 4: Build Hidden Folders & Dummy Folders Card**
- [ ] **Step 5: Add sticky/prominent Save Profile FAB/Button**

---

### Task 4: Redesign Settings Tab
**Files:**
- Modify: `D:\thenile\app\src\main\java\com\thenile\vault\ui\AdminActivity.kt`

- [ ] **Step 1: Build Category Filter Row (All, Decoy, Triggers, Security, Backup)**
- [ ] **Step 2: Build Real Lock Screen Decoy Section using `SelectableOptionCard` and `PreferenceSwitchRow`**
- [ ] **Step 3: Build App Disguise & Launch Triggers Section using `PreferenceSwitchRow`**
- [ ] **Step 4: Build Security & Authentication Section**
- [ ] **Step 5: Build Backup & Data Section**
- [ ] **Step 6: Build Save Settings & Apply Button**

---

### Task 5: Build, Install, and Verify
**Files:**
- Build: `./gradlew assembleDebug`
- Install: `adb install -r ...`

- [ ] **Step 1: Build debug APK**
- [ ] **Step 2: Install APK on emulator-5554**
- [ ] **Step 3: Capture screenshots of Profiles tab and Settings tab**
- [ ] **Step 4: Run `graphify update .`**
