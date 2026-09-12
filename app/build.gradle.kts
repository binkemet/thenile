plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("net.mullvad.rust-android") version "0.10.1"
}

android {
    namespace = "com.thenile.vault"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.thenile.vault"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    // Two editions from one codebase:
    //  - xposed: sideloaded APK, includes the LSPosed hook (real-lockscreen decoy trigger). Default.
    //  - aosp:   root-only, no Xposed. For baking into an AOSP ROM (GrapheneOS etc.) as a system
    //            app — same appId so it can replace the sideloaded build. The hook source, assets,
    //            and manifest meta-data live in src/xposed/ and are simply absent here.
    flavorDimensions += "edition"
    productFlavors {
        create("xposed") {
            dimension = "edition"
            isDefault = true
        }
        create("aosp") {
            dimension = "edition"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      // Needed for the Shizuku user-service AIDL interface (shizuku/IShellService.aidl) —
      // Shizuku.newProcess isn't public API on the current shizuku-api version, so shell-UID
      // commands run through a small self-hosted AIDL service instead (see ShizukuShell.kt).
      aidl = true
      buildConfig = true
      shaders = false
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("build/rustJniLibs/android")
        }
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      // Extract native libs to nativeLibraryDir so libdmcrypt.so can be exec'd as root via libsu.
      jniLibs {
        useLegacyPackaging = true
      }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

cargo {
    module = "rust_crypto"
    libname = "rust_crypto"
    targets = listOf("arm", "arm64", "x86", "x86_64")
}

afterEvaluate {
    tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach {
        dependsOn("cargoBuild")
    }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation("androidx.fragment:fragment-ktx:1.8.2")

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.json:json:20240303")


  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // TheNile Dependencies
  implementation("com.github.topjohnwu.libsu:core:5.2.2")
  // Xposed API only compiles into the xposed flavor; the aosp flavor has no hook source to build.
  "xposedCompileOnly"("io.github.libxposed:api:102.0.0")
  implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")
  // Shell-UID (ADB/wireless-debugging) privilege tier for devices without root, e.g. GrapheneOS.
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
}
