# Graph Report - thenile  (2026-08-17)

## Corpus Check
- 28 files · ~49,564 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 242 nodes · 365 edges · 21 communities (16 shown, 5 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 18 edges (avg confidence: 0.82)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `3df0d3f9`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Feature Catalog & Decoy UI
- Encrypted Backup & Settings
- System Event Receivers & Services
- Root Storage Mount / LUKS
- Vault State Machine
- Backup & Animation Design Docs
- Rust dm-crypt Kernel Layer
- Rust JNI Crypto Bridge
- Admin UI Composables
- Xposed PackageManager Hook
- PIN Prompt UI
- Config Tests
- Gradle Wrapper
- Trace Cleaner
- Config
- BackupManager
- BootAnimation

## God Nodes (most connected - your core abstractions)
1. `The Nile` - 18 edges
2. `SettingsManager` - 17 edges
3. `VaultStateManager` - 14 edges
4. `Profile` - 12 edges
5. `AdminScreen()` - 12 edges
6. `create_crypt()` - 9 edges
7. `remove()` - 8 edges
8. `mountRealContainer()` - 8 edges
9. `DummyDir` - 8 edges
10. `User-Selectable Launch Methods Design` - 8 edges

## Surprising Connections (you probably didn't know these)
- `FakeCrashScreen.kt` --semantically_similar_to--> `Calculator Decoy App Disguise`  [INFERRED] [semantically similar]
  docs/superpowers/specs/2026-08-11-fake-crash-and-encrypted-backup-design.md → README.md
- `Graphify Workflow Guidance` --references--> `The Nile`  [INFERRED]
  AGENTS.md → README.md
- `The Nile` --implements--> `com.thenile.vault Package`  [INFERRED]
  README.md → docs/superpowers/plans/2026-08-11-animations-and-performance-optimization-plan.md
- `Convenience Shortcuts (QS Tile, Deep Links, Volume Keys)` --conceptually_related_to--> `User-Selectable Launch Methods Design`  [INFERRED]
  README.md → docs/superpowers/specs/2026-08-11-user-selectable-launch-methods-design.md
- `Changelog v1.0 Initial Release` --references--> `The Nile`  [EXTRACTED]
  CHANGELOG.md → README.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Vault Launch Methods opening AdminActivity** — secret_code_receiver, nile_tile_service, nile_deep_link, nile_accessibility_service, calculator_activity, admin_activity_launch [EXTRACTED 1.00]
- **Encrypted .nile Backup Pipeline** — fake_crash_and_encrypted_backup_design_backupmanager, rust_crypto, argon2id_kdf, nile_file_format, saf_workflow [EXTRACTED 1.00]
- **Per-Profile Decoy PIN Resolution Flow** — profile_decoy_pin_redesign_profile, profile_decoy_pin_redesign_settingsmanager, decoy_pin_matching, profile_decoy_pin_redesign_promptactivity [EXTRACTED 1.00]

## Communities (21 total, 5 thin omitted)

### Community 0 - "Feature Catalog & Decoy UI"
Cohesion: 0.07
Nodes (40): AdminActivity (Vault Admin UI), Graphify Workflow Guidance, Animations & Performance Optimization Plan, App Hiding via LSPosed/Xposed, Argon2id Key Derivation, CalculatorActivity.kt, Calculator Decoy App Disguise, Changelog v1.0 Initial Release (+32 more)

### Community 1 - "Encrypted Backup & Settings"
Cohesion: 0.13
Nodes (9): DummyDir, getInstance(), Context, SharedPreferences, Profile, SettingsManager, BackupTest, MockSettingsManager (+1 more)

### Community 2 - "System Event Receivers & Services"
Cohesion: 0.11
Nodes (10): AccessibilityEvent, AccessibilityService, Context, SecretCodeReceiver, NileAccessibilityService, NileTileService, BroadcastReceiver, Intent (+2 more)

### Community 3 - "Root Storage Mount / LUKS"
Cohesion: 0.24
Nodes (14): deriveKey(), exists(), getUsers(), hideProfile(), isVaultMounted(), loopFor(), mountDecoyDirectory(), mountRealContainer() (+6 more)

### Community 4 - "Vault State Machine"
Cohesion: 0.14
Nodes (9): getInstance(), Context, SharedPreferences, VaultState, DECOY, LOCKED, UNLOCKED, VaultStateManager (+1 more)

### Community 5 - "Backup & Animation Design Docs"
Cohesion: 0.18
Nodes (11): Animations & Performance Optimization Design, AnimatedContent Tab Transitions, Per-Code Decoy Matching Getters, derivedStateOf Dirty Checking, Multi-profile Support with Per-Profile Decoy PINs, Per-Profile Decoy PIN & Switching Design, Per-Profile Decoy PIN & Switching Plan, Profile Data Model (+3 more)

### Community 6 - "Rust dm-crypt Kernel Layer"
Cohesion: 0.33
Nodes (14): base_header(), create_crypt(), dm_ioctl(), DmIoctl, DmTargetSpec, errno_str(), header_bytes(), iowr() (+6 more)

### Community 7 - "Rust JNI Crypto Bridge"
Cohesion: 0.27
Nodes (10): Java_com_thenile_vault_backup_BackupManager_decryptPayloadNative(), Java_com_thenile_vault_backup_BackupManager_encryptPayloadNative(), Java_com_thenile_vault_root_StorageMountManager_deriveKey(), Java_com_thenile_vault_root_StorageMountManager_hashPin(), Java_com_thenile_vault_root_StorageMountManager_verifyPin(), jboolean, JByteArray, JClass (+2 more)

### Community 8 - "Admin UI Composables"
Cohesion: 0.24
Nodes (11): androidx, AdminActivity, AdminScreen(), AppPickerDialog(), authenticate(), DialCodeDropdown(), Bundle, ProfileListDialog() (+3 more)

### Community 9 - "Xposed PackageManager Hook"
Cohesion: 0.18
Nodes (17): decoyUserId(), filterResult(), getUserIdFromObject(), hasOneTimeUnlockUsesLeft(), hookDirectQueries(), hookInProcessUserManager(), hookPm(), hookSettingsApp() (+9 more)

### Community 10 - "PIN Prompt UI"
Cohesion: 0.23
Nodes (7): DecoyBootScreen(), Bundle, PinKey(), PinPad(), PinScreen(), PromptActivity, ComponentActivity

### Community 12 - "Gradle Wrapper"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **25 isolated node(s):** `DmTargetSpec`, `Config`, `StorageMountManager`, `TraceCleaner`, `LOCKED` (+20 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **5 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `AdminScreen()` connect `Admin UI Composables` to `Encrypted Backup & Settings`, `System Event Receivers & Services`, `Vault State Machine`?**
  _High betweenness centrality (0.107) - this node is a cross-community bridge._
- **Why does `SettingsManager` connect `Encrypted Backup & Settings` to `Admin UI Composables`, `BackupManager`?**
  _High betweenness centrality (0.074) - this node is a cross-community bridge._
- **Why does `VaultStateManager` connect `Vault State Machine` to `Admin UI Composables`?**
  _High betweenness centrality (0.054) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `The Nile` (e.g. with `Graphify Workflow Guidance` and `com.thenile.vault Package`) actually correct?**
  _`The Nile` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 3 inferred relationships involving `Profile` (e.g. with `.testDecoyPinMatchingForFallbackCode()` and `.testDecoyPinMatchingForSpecificCode()`) actually correct?**
  _`Profile` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `AdminScreen()` (e.g. with `VaultStateManager` and `Intent`) actually correct?**
  _`AdminScreen()` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `DmTargetSpec`, `Config`, `StorageMountManager` to the rest of the system?**
  _25 weakly-connected nodes found - possible documentation gaps or missing edges._