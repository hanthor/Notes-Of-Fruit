plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Version comes from Gradle properties so the release workflow can derive it
// from the git tag:  ./gradlew assembleRelease -PversionName=0.1.2 -PversionCode=102
// The defaults are what a plain local build gets.
val appVersionName: String = providers.gradleProperty("versionName").getOrElse("0.0.2")
val appVersionCode: Int = providers.gradleProperty("versionCode").map { it.toInt() }.getOrElse(2)

// Release signing: prefer a keystore supplied through the environment (wired
// from GitHub Actions secrets in .github/workflows/release.yml); fall back to
// the committed alpha keystore. See docs/signing.md for rotating to a private
// key and what that means for people upgrading.
fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
val envKeystorePath: String? = env("NOF_KEYSTORE_PATH")

android {
    // The namespace (R class / Kotlin package) stays com.example.applenotes;
    // only the installed application ID changed. See README "Install".
    namespace = "com.example.applenotes"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.hanthor.notesoffruit"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // Stable keystore so every release-tagged build is signed with the
        // SAME key. Without this, GH Actions generates a fresh
        // ~/.android/debug.keystore per runner and users can't upgrade across
        // releases (Android rejects an APK update with a different signing
        // certificate).
        //
        // The fallback keystore's password is committed alongside it on
        // purpose — this is an alpha self-signed cert, not a Play Store key.
        // Anyone who can pull the repo can rebuild & sign their own APK; that's
        // the expected level of trust for a research project. Setting the
        // NOF_KEYSTORE_* environment variables overrides it.
        create("releaseAlpha") {
            if (envKeystorePath != null) {
                storeFile = file(envKeystorePath)
                storePassword = env("NOF_KEYSTORE_PASSWORD")
                    ?: error("NOF_KEYSTORE_PATH is set but NOF_KEYSTORE_PASSWORD is not")
                keyAlias = env("NOF_KEY_ALIAS")
                    ?: error("NOF_KEYSTORE_PATH is set but NOF_KEY_ALIAS is not")
                keyPassword = env("NOF_KEY_PASSWORD") ?: storePassword
            } else {
                storeFile = file("keystore/release.keystore")
                storePassword = "notesoffruit"
                keyAlias = "notes-of-fruit"
                keyPassword = "notesoffruit"
            }
        }
    }

    buildTypes {
        debug {
            // Lets a debug build sit next to a release install on one device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 on. proguard-rules.pro keeps every class in the app's own
            // packages, so only library code is shrunk/obfuscated.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("releaseAlpha")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
      unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)

  // HTTP + JSON + persistence
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.logging)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.core.splashscreen)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
