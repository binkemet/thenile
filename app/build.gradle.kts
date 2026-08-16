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
        versionCode = 1
        versionName = "1.0"
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
      aidl = false
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
  compileOnly("io.github.libxposed:api:102.0.0")
  implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")
}
