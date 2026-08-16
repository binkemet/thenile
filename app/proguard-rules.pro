# TheNile Stealth Engine — ProGuard rules
#
# Keep the Xposed hook entry point (referenced by xposed_init asset).
-keep class com.thenile.vault.xposed.PackageManagerHook { *; }

# Keep JNI methods — Rust expects exact symbol names.
-keepclassmembers class com.thenile.vault.root.StorageMountManager {
    static native *;
}

# Keep the Config object (read by the hook in other processes via reflection).
-keep class com.thenile.vault.Config { *; }

# Keep JNI native methods
-keepclasseswithmembernames class com.thenile.vault.backup.BackupManager {
    native <methods>;
}

# Keep Xposed module
-keep class com.thenile.vault.xposed.** { *; }

# Keep libsu
-keep class com.topjohnwu.superuser.** { *; }

# Keep data classes used for JSON serialization
-keep class com.thenile.vault.state.Profile { *; }
-keep class com.thenile.vault.state.DummyDir { *; }

# Keep AndroidX Startup Provider (prevent ClassNotFoundException on launch)
-keep class androidx.startup.InitializationProvider { *; }
