plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

// Windows' Gradle test worker cannot load Kotlin test classes from a classpath
// containing non-ASCII path segments. Keep generated build output in an ASCII
// Gradle cache only on affected Windows workspaces; source and artifacts remain
// in the repository and CI keeps the default layout.
if (System.getProperty("os.name").startsWith("Windows") && projectDir.absolutePath.any { it.code > 127 }) {
  layout.buildDirectory.set(file("${System.getProperty("user.home")}/.gradle/campusai-build/android-app"))
}

android {
  namespace = "com.campusai"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  ndkVersion = "28.2.13676358"

  defaultConfig {
    applicationId = "com.aistudio.campusai.ywtpzx"
    minSdk = 24
    targetSdk = 36
    versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 3
    versionName = System.getenv("VERSION_NAME") ?: "2.0.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild {
      cmake {
        cppFlags += listOf("-std=c++17")
        arguments += listOf("-DANDROID_STL=c++_shared")
      }
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    isCoreLibraryDesugaringEnabled = true
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging {
    jniLibs {
      // MNN ships the shared C++ runtime while the local JNI target also asks
      // the NDK for it. A clean Linux build sees both inputs; package one copy.
      pickFirsts += "lib/arm64-v8a/libc++_shared.so"
    }
  }
  sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
  // Robolectric reads Room migration schemas through the debug app's
  // AssetManager; keep them out of release builds while making local tests real.
  sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("appfunctions:aggregateAppFunctions", "true")
}


// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  // Allow a migration candidate without changing the current production config.
  propertiesFileName = providers.gradleProperty("campusBackendConfig").getOrElse(".env")
  defaultPropertiesFileName = ".env.example"
  // Only public Supabase client configuration may enter the APK. Everything
  // else in a developer's local .env (especially old provider keys) is ignored.
  ignoreList.add("^(?!SUPABASE_URL$|SUPABASE_ANON_KEY$).*$")
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.mlkit.text.recognition.chinese)
  implementation(libs.okhttp)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.appfunctions)
  implementation(libs.androidx.health.connect)
  implementation(libs.androidx.exifinterface)
  implementation(libs.koog.prompt.executor.model)
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  testImplementation(libs.androidx.room.testing)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation(libs.androidx.room.testing)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.androidx.appfunctions.compiler)
}

// connectedAndroidTest installs and then removes the target package. On a personal phone that
// also deletes Caesar's private model files and download staging directory. Require the guarded
// repository script to approve the device before Gradle is allowed to enter that lifecycle.
gradle.taskGraph.whenReady {
  val connectedAndroidTestScheduled = allTasks.any { task ->
    task.project == project && task.name.startsWith("connected") && task.name.endsWith("AndroidTest")
  }
  val guardVerified = providers.gradleProperty("caesarConnectedTestGuard").orNull == "verified"
  if (connectedAndroidTestScheduled && !guardVerified) {
    throw GradleException(
      "Direct connected Android tests are blocked because they can uninstall Caesar and erase " +
        "private model data. Use scripts/run-android-device-tests.ps1 with an isolated emulator.",
    )
  }
}
