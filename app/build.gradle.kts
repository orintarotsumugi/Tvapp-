plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.kurdishtv.live"
    minSdk = 24
    targetSdk = 36

    // Android refuses to install a build whose versionCode is not at least the
    // installed one, and reports it as a broken update. CI overrides this with the
    // run number; local builds used to fall back to 1, which could never be
    // installed over a published release. kurdishTvVersionCode in
    // gradle.properties is the local floor and must be kept at or above the last
    // published release.
    val ciVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull()
    val localVersionCode =
        project.findProperty("kurdishTvVersionCode")?.toString()?.toIntOrNull() ?: 1
    val resolvedVersionCode = ciVersionCode ?: localVersionCode
    versionCode = resolvedVersionCode

    // The human-facing version (Settings → About) is kept separate from versionCode
    // so a CI run number never leaks into the UI as "1.0.36265844407".
    versionName =
        project.findProperty("kurdishTvVersionName")?.toString()?.takeIf { it.isNotBlank() }
            ?: "1.0.$resolvedVersionCode"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    // Release signing, restored. See SIGNING.md for why this is not optional.
    //
    // With this block commented out, the release buildType fell back to AGP's
    // default *debug* config — and `debug.keystore` is gitignored, so every CI
    // runner generated a fresh random key for every release. Each published APK
    // was therefore signed with a different key, which breaks the app in two
    // ways at once:
    //
    //  - The in-app updater downloads the new APK, verifies it against the
    //    installed signature (`ApkInstaller.isSignedBySameCertificate`) and
    //    correctly refuses to install it — so the auto-update flow can never
    //    succeed for anyone. Android itself would refuse too
    //    (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
    //  - Every release requires a full uninstall, losing every viewer's
    //    favourites and watch history.
    //
    // The key is committed (`release-key.jks`, PKCS12, alias `upload`) precisely
    // so every machine and every CI run signs identically. Order of preference:
    // a real upload key from the environment, then the committed key, and only
    // then the local debug key — loudly, because that is the original bug.
    create("release") {
      val envKeystore = System.getenv("KEYSTORE_PATH")?.let { file(it) }
      val bundledKeystore = file("${rootDir}/release-key.jks")

      when {
        envKeystore != null && envKeystore.exists() -> {
          storeFile = envKeystore
          storePassword = System.getenv("STORE_PASSWORD")
          keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
          keyPassword = System.getenv("KEY_PASSWORD")
        }
        bundledKeystore.exists() -> {
          storeFile = bundledKeystore
          storePassword = System.getenv("STORE_PASSWORD") ?: "tvapp123r"
          keyAlias = "upload"
          keyPassword = System.getenv("KEY_PASSWORD") ?: "tvapp123r"
        }
        else -> {
          rootProject.logger.warn(
            "WARNING: release-key.jks is missing. Falling back to the debug key, " +
              "which will NOT match previously published builds — every installed " +
              "copy will refuse to update over this one. Restore release-key.jks " +
              "(see SIGNING.md) or set KEYSTORE_PATH."
          )
        }
      }
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
      // Lossless PNG optimisation. Safe here because every image is referenced as a
      // drawable/mipmap resource rather than loaded by raw file name.
      isCrunchPngs = true
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // The committed release key when it exists, and the debug key only as the
      // loudly-warned last resort configured above. Signed with the same key on
      // every machine and every CI run, which is what makes the in-app updater's
      // signature check — and Android's own upgrade rule — pass at all.
      signingConfig = if (signingConfigs.getByName("release").storeFile?.exists() == true) {
        signingConfigs.getByName("release")
      } else {
        signingConfigs.getByName("debug")
      }
    }
    debug {
      signingConfig = signingConfigs.getByName("debug")
      // Keep debug iteration fast: R8 and resource shrinking are the slowest parts of
      // the release build and buy nothing while developing.
      isMinifyEnabled = false
      isShrinkResources = false
    }
  }
  androidResources {
    // Keep only the default (English) resources, and drop every other locale.
    //
    // This app has no translations of its own: `res/values/strings.xml` holds one
    // string, and every piece of visible text is written inline in Kotlin. So there
    // is nothing here that *would* answer to a device set to another language — the
    // Kurdish, Arabic and English labels on screen are the same bytes whatever the
    // system locale is.
    //
    // What the filter does remove is every other language that the *dependencies*
    // ship. Material 3, AndroidX AppCompat, Media3, Coil and the support libraries
    // between them contribute `values-<locale>/strings.xml` for eighty-odd locales:
    // accessibility and error strings the app never displays, and system-UI
    // fragments that do not apply to a Compose tree. None of it was reachable, all
    // of it was in the APK, and `isShrinkResources` cannot remove it — resource
    // shrinking keeps a resource that any *declared* locale configuration can still
    // select, which until now was every locale on earth.
    //
    // Note this is `androidResources.localeFilters`, not the older
    // `defaultConfig.resourceConfigurations`. AGP deprecated the latter in 8.8 and
    // this project is on 9.1.1, so the old spelling is on its way out.
    localeFilters += listOf("en")
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/*.version"
      // Debug metadata that the Kotlin and AGP toolchains leave in the merged
      // manifest's directory, and which no part of this app reads. Small, but it
      // is free and it is pure noise in an APK.
      excludes += "/META-INF/*.kotlin_module"
      excludes += "/META-INF/*.kotlin_builtins"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  lint {
    abortOnError = false
    checkReleaseBuilds = false
    ignoreWarnings = true
  }
}

/**
 * Audits every HLS stream in the bundled catalogue against the live network.
 *
 *   ./gradlew auditStreams
 *
 * Not part of any build task: this makes real network requests, takes a minute
 * or two, and must never be something `assembleRelease` can fail because a
 * broadcaster is having a bad afternoon. It is a maintenance tool, invoked by
 * hand, whose output is what justifies changing a stream URL.
 *
 * It is deliberately a separate script rather than a unit test. A test that
 * fails when a third-party origin 404s is a test that gets deleted.
 */
tasks.register<Exec>("auditStreams") {
    group = "verification"
    description = "Verify every catalogue HLS stream end to end (master, variant, media segment)."
    commandLine(
        "python3",
        rootProject.file("scripts/audit_streams.py"),
        "--concurrency", "6",
        "--attempts", "3"
    )
    // Fails the task when a stream is dead, so it is usable as a gate in a
    // maintenance branch even though it is never wired into the build.
    isIgnoreExitValue = false
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  // `material.icons.core` was on the runtime classpath and never imported. Every
  // glyph in this app is a hand-written vector drawable under res/drawable (see
  // SvgIcon), so the whole icons artifact was dead weight in the APK.
  // implementation(libs.androidx.compose.material.icons.core)
  // implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  // ui-tooling-preview only supplies @Preview annotations at compile time. It was on
  // the release runtime classpath, so move it to debug where it belongs.
  debugImplementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  // Lets ART AOT-compile the app's own startup path from the rules in
  // src/main/baseline-prof.txt instead of discovering it at runtime. This is the
  // dependency that matters on API 24-28, where there is no Play profile delivery.
  // The library alone does nothing — the rules file next to AndroidManifest.xml
  // is what it installs.
  implementation(libs.androidx.profileinstaller)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.exoplayer.hls)
  implementation(libs.androidx.media3.datasource.okhttp)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // `logging.interceptor` was declared but never applied: no `HttpLoggingInterceptor`
  // is constructed anywhere in the app. It pulled okhttp's logging artifact into
  // every build for nothing.
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // Start.io (formerly StartApp) in-app ads, pinned to 5.3.1 rather than the newest
  // 5.3.2, and both reasons are hard build failures rather than warnings.
  //
  // 5.3.2's AAR metadata requires compileSdk 37, which this project does not use and
  // AGP refuses to build against rather than warning about. It is the only release in
  // the catalogue that constrains it; every version below it asks for nothing.
  //
  // 5.3.2 also declares kotlin-stdlib 2.4.0 as a plain compile dependency rather than
  // a range, so it outvotes the 2.2.10 stdlib this project's compiler ships. A
  // compiler that can read metadata only up to 2.3.0 does not degrade gracefully:
  // "Class 'kotlin.Lazy' was compiled with an incompatible version of Kotlin", and
  // then an unresolved `lazy`, `javaClass` and property-delegate error in every Kotlin
  // file in the app, the SDK's own included. 5.3.1 declares 2.0.0, older than this
  // project's, so the stdlib resolves to the compiler's own version and nothing has to
  // be excluded. Re-check both of these before moving the pin.
  //
  // The SDK carries its own consumer ProGuard rules (proguard.txt in the AAR), so no
  // hand-written keeps are needed here either.
  implementation(libs.startapp.inapp.sdk)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
