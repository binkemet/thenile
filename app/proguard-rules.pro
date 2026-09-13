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

# Tink (via androidx.security-crypto, used for EncryptedSharedPreferences) references optional
# build-time annotations that aren't on the runtime classpath — silence R8 (safe to omit).
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
# Keep Tink so EncryptedSharedPreferences keeps working after minification.
-keep class com.google.crypto.tink.** { *; }
# Tink's optional remote-key downloader pulls in the Google HTTP client + joda-time, which we
# don't ship or use (EncryptedSharedPreferences only needs local AEAD) — silence those too.
-dontwarn com.google.api.client.http.**
-dontwarn com.google.errorprone.annotations.InlineMe
-dontwarn javax.annotation.concurrent.ThreadSafe
-dontwarn org.joda.time.**
