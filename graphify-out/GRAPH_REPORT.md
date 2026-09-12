# Graph Report - D:/thenile  (2026-09-12)

## Corpus Check
- 76 files · ~71,911 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 475 nodes · 741 edges · 47 communities (29 shown, 18 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 31 edges (avg confidence: 0.87)
- Token cost: 60,000 input · 10,667 output

## Community Hubs (Navigation)
- Settings & Vault Config Tests
- Feature Docs & Changelog
- Admin UI & Quick Settings Tile
- Xposed Package Manager Hook
- Vault State Management
- SoftVault (SAF-based Hiding)
- Storage Mount Manager
- Rust Crypto JNI Bridge
- PIN Prompt UI
- Rust dm-crypt Device Mapper
- HiddenVolume (dm-crypt Container)
- Privilege Tier Detection
- Decoy Trigger Receivers
- Hidden App Manager (Decoy/Real Swap)
- App Context Provider
- App Data Vault (Tar Snapshot/Restore)
- Privileged Shell Execution
- Accessibility Service
- Dead Man's Switch
- SAF Blob Store
- ROM Lockscreen Trigger Docs
- Encrypted Backup Manager
- Device Admin Receiver
- SoftVault Unit Tests
- Dead Man's Switch Receiver
- USB Plugged Receiver
- Dead Man's Switch Tests
- Wrong PIN Switch Tests
- HiddenVolume Device Tests
- Decoy Action Runner
- Wrong PIN Lockdown Switch
- AppDataVault Unit Tests
- Config Unit Tests
- HiddenVolume Unit Tests
- Gradle Wrapper Script
- Trace Cleaner
- USB Plugged Lockdown Switch
- Shizuku Shell User Service
- AGENTS.md Graphify Notes
- Mount-Namespace Regression Test
- AppDataVault Device Test
- SoftVault Device Test
- Config Object

## God Nodes (most connected - your core abstractions)
1. `SettingsManager` - 21 edges
2. `Vault` - 20 edges
3. `The Nile (product)` - 20 edges
4. `VaultStateManager` - 18 edges
5. `SoftVault` - 16 edges
6. `AdminScreen()` - 16 edges
7. `v1.0 Initial Release` - 13 edges
8. `HiddenVolume` - 12 edges
9. `AppDataVault` - 11 edges
10. `HiddenAppManager` - 11 edges

## Surprising Connections (you probably didn't know these)
- `F-Droid v1.0 changelog entry` --shares_data_with--> `v1.0 Initial Release`  [INFERRED]
  metadata/en-US/changelogs/1.txt → CHANGELOG.md
- `F-Droid full description of The Nile` --shares_data_with--> `The Nile (product)`  [INFERRED]
  metadata/en-US/full_description.txt → README.md
- `F-Droid short description / tagline` --conceptually_related_to--> `The Nile (product)`  [INFERRED]
  metadata/en-US/short_description.txt → README.md
- `F-Droid full description of The Nile` --conceptually_related_to--> `Root Access requirement (KernelSU or Magisk)`  [AMBIGUOUS]
  metadata/en-US/full_description.txt → README.md
- `Multi-state vault (Locked/Unlocked/Decoy)` --shares_data_with--> `Multi-state vault (Locked/Unlocked/Decoy)`  [INFERRED]
  README.md → CHANGELOG.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **ROM Decoy-PIN Interception Flow** — docs_rom_lockscreen_trigger_settingsmanager_synctosystem, docs_rom_lockscreen_trigger_thenile_config_json, docs_rom_lockscreen_trigger_keyguard_patch, docs_rom_lockscreen_trigger_lockscreen_decoy_broadcast, docs_rom_lockscreen_trigger_lockscreendecoyreceiver [EXTRACTED 1.00]
- **The Nile v1.0 Feature Set Documentation** — readme_the_nile, changelog_v1_0, metadata_en_us_full_description_the_nile, metadata_en_us_changelogs_1_v1_0_release_notes [INFERRED 0.85]

## Communities (47 total, 18 thin omitted)

### Community 0 - "Settings & Vault Config Tests"
Cohesion: 0.09
Nodes (10): HiddenAppManagerDeviceTest, DummyDir, getInstance(), Context, SharedPreferences, SettingsManager, Vault, BackupTest (+2 more)

### Community 1 - "Feature Docs & Changelog"
Cohesion: 0.08
Nodes (35): App hiding via LSPosed/Xposed, Calculator decoy app disguise, Quick Settings tile, deep links, volume key shortcuts, Dial code activation (*#*#CODE#*#*), Directory hiding with LUKS containers, AES-256-GCM encrypted backups (Rust native crypto), Fake crash screen disguise (long-press bypass), Launcher icon hiding (+27 more)

### Community 2 - "Admin UI & Quick Settings Tile"
Cohesion: 0.12
Nodes (25): NileTileService, AccountPickerDialog(), AdminActivity, AdminScreen(), AndroidUser, AppPickerDialog(), authenticate(), createDecoyAndroidUser() (+17 more)

### Community 3 - "Xposed Package Manager Hook"
Cohesion: 0.15
Nodes (23): decoyUserId(), filterResult(), getUserIdFromObject(), hookDirectQueries(), hookInProcessUserManager(), hookPm(), hookSettingsApp(), isActuallySystemServerProcess() (+15 more)

### Community 4 - "Vault State Management"
Cohesion: 0.12
Nodes (9): getInstance(), Context, SharedPreferences, VaultState, DECOY, LOCKED, UNLOCKED, VaultStateManager (+1 more)

### Community 5 - "SoftVault (SAF-based Hiding)"
Cohesion: 0.26
Nodes (4): Context, Uri, SoftVault, JSONObject

### Community 6 - "Storage Mount Manager"
Cohesion: 0.23
Nodes (17): decryptFileNative(), deriveKey(), encryptFileNative(), exists(), getUsers(), hideVault(), isVaultMounted(), Context (+9 more)

### Community 7 - "Rust Crypto JNI Bridge"
Cohesion: 0.28
Nodes (12): Java_com_thenile_vault_backup_BackupManager_decryptPayloadNative(), Java_com_thenile_vault_backup_BackupManager_encryptPayloadNative(), Java_com_thenile_vault_root_StorageMountManager_decryptFileNative(), Java_com_thenile_vault_root_StorageMountManager_deriveKey(), Java_com_thenile_vault_root_StorageMountManager_encryptFileNative(), Java_com_thenile_vault_root_StorageMountManager_hashPin(), Java_com_thenile_vault_root_StorageMountManager_verifyPin(), jboolean (+4 more)

### Community 8 - "PIN Prompt UI"
Cohesion: 0.20
Nodes (8): android, DecoyBootScreen(), Bundle, PinKey(), PinPad(), PinScreen(), PromptActivity, ComponentActivity

### Community 9 - "Rust dm-crypt Device Mapper"
Cohesion: 0.33
Nodes (14): base_header(), create_crypt(), dm_ioctl(), DmIoctl, DmTargetSpec, errno_str(), header_bytes(), iowr() (+6 more)

### Community 10 - "HiddenVolume (dm-crypt Container)"
Cohesion: 0.30
Nodes (4): HiddenVolume, Role, DECOY, HIDDEN

### Community 11 - "Privilege Tier Detection"
Cohesion: 0.20
Nodes (6): Context, PrivilegeManager, PrivilegeTier, NONE, ROOT, SHIZUKU

### Community 12 - "Decoy Trigger Receivers"
Cohesion: 0.19
Nodes (9): BroadcastReceiver, Context, Intent, LockscreenDecoyReceiver, handleTriggerCode(), BroadcastReceiver, Context, Intent (+1 more)

### Community 14 - "App Context Provider"
Cohesion: 0.24
Nodes (5): AppContextProvider, Uri, ContentProvider, ContentValues, Cursor

### Community 16 - "Privileged Shell Execution"
Cohesion: 0.27
Nodes (5): PrivilegedShell, ShellResult, IShellService, ShizukuShell, Shizuku

### Community 17 - "Accessibility Service"
Cohesion: 0.22
Nodes (4): AccessibilityEvent, AccessibilityService, NileAccessibilityService, KeyEvent

### Community 18 - "Dead Man's Switch"
Cohesion: 0.39
Nodes (3): DeadManSwitch, Context, PendingIntent

### Community 19 - "SAF Blob Store"
Cohesion: 0.56
Nodes (3): Context, Uri, SafBlobStore

### Community 20 - "ROM Lockscreen Trigger Docs"
Cohesion: 0.22
Nodes (8): aosp build (no hook, ROM self-check), Keyguard password-check patch, com.thenile.vault.action.LOCKSCREEN_DECOY broadcast, LockscreenDecoyReceiver, MANAGE_USERS permission (signature|privileged), ROM Lockscreen Decoy Trigger (AOSP edition), /data/system/thenile_config.json, xposed build (system_server hook)

### Community 22 - "Device Admin Receiver"
Cohesion: 0.38
Nodes (4): Context, Intent, NileDeviceAdminReceiver, DeviceAdminReceiver

### Community 24 - "Dead Man's Switch Receiver"
Cohesion: 0.33
Nodes (4): DeadManSwitchReceiver, BroadcastReceiver, Context, Intent

### Community 25 - "USB Plugged Receiver"
Cohesion: 0.33
Nodes (4): BroadcastReceiver, Context, Intent, UsbPluggedReceiver

### Community 34 - "Gradle Wrapper Script"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 35 - "Trace Cleaner"
Cohesion: 0.67
Nodes (3): cleanAllTraces(), cleanTraces(), TraceCleaner

### Community 38 - "AGENTS.md Graphify Notes"
Cohesion: 0.67
Nodes (3): GRAPH_REPORT.md, graphify skill/tool, graphify-out knowledge graph directory

## Ambiguous Edges - Review These
- `Root Access requirement (KernelSU or Magisk)` → `F-Droid full description of The Nile`  [AMBIGUOUS]
  metadata/en-US/full_description.txt · relation: conceptually_related_to

## Knowledge Gaps
- **25 isolated node(s):** `DmTargetSpec`, `Config`, `DECOY`, `HIDDEN`, `ROOT` (+20 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **18 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `Root Access requirement (KernelSU or Magisk)` and `F-Droid full description of The Nile`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `Vault` connect `Settings & Vault Config Tests` to `Admin UI & Quick Settings Tile`, `Decoy Action Runner`, `Hidden App Manager (Decoy/Real Swap)`, `Encrypted Backup Manager`?**
  _High betweenness centrality (0.046) - this node is a cross-community bridge._
- **Why does `AdminScreen()` connect `Admin UI & Quick Settings Tile` to `Settings & Vault Config Tests`, `Vault State Management`?**
  _High betweenness centrality (0.046) - this node is a cross-community bridge._
- **Why does `SettingsManager` connect `Settings & Vault Config Tests` to `Admin UI & Quick Settings Tile`?**
  _High betweenness centrality (0.033) - this node is a cross-community bridge._
- **Are the 5 inferred relationships involving `Vault` (e.g. with `.decoyAndRealSwapCycle()` and `.uninstallAndReinstallCycle()`) actually correct?**
  _`Vault` has 5 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `The Nile (product)` (e.g. with `F-Droid full description of The Nile` and `F-Droid short description / tagline`) actually correct?**
  _`The Nile (product)` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `DmTargetSpec`, `Config`, `DECOY` to the rest of the system?**
  _25 weakly-connected nodes found - possible documentation gaps or missing edges._