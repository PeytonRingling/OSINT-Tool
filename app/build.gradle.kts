plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "vip.ringling.recon"
    compileSdk = 37

    defaultConfig {
        applicationId = "vip.ringling.recon"
        minSdk = 29
        targetSdk = 37
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        // "owner/repo" for in-app updates. CI supplies it (GITHUB_REPOSITORY); the source tree names no account. Local builds leave it empty (they can't update anyway: versionCode 1).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
    }

    // Release builds are signed only with the private release key from CI secrets (see README, "Release signing"). There is no fallback:
    // without all four values a release build fails. Debug builds use the standard local debug key.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    val releaseSecrets = listOf("RELEASE_KEYSTORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD").associateWith { System.getenv(it)?.takeIf { v -> v.isNotEmpty() } }
    val canSignRelease = releaseKeystore != null && file(releaseKeystore).isFile && releaseSecrets.values.all { it != null }
    signingConfigs {
        if (canSignRelease) create("release") {
            storeFile = file(releaseKeystore!!)
            storePassword = releaseSecrets.getValue("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = releaseSecrets.getValue("RELEASE_KEY_ALIAS")
            keyPassword = releaseSecrets.getValue("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Robolectric on JDK 17+ needs reflective access to these packages.
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.nio=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
                it.maxHeapSize = "2g"
            }
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}


dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splash)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.wsc)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.browser)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.sqlcipher) { artifact { type = "aar" } }
    implementation(libs.zxing.core)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.exif)
    implementation(libs.androidx.work)
    implementation(libs.androidx.window)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)
    implementation(libs.libphonenumber)
    implementation(libs.phone.geocoder)
    implementation(libs.phone.carrier)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("docs/screenshots"))
}

// A release APK without the release key would be unsigned (or, worse, signed with something else): stop instead.
tasks.configureEach {
    if (name.startsWith("package") && name.endsWith("Release")) doFirst {
        val missing = listOf("RELEASE_KEYSTORE_FILE", "RELEASE_KEYSTORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD").filter { System.getenv(it).isNullOrEmpty() }
        if (missing.isNotEmpty() || !file(System.getenv("RELEASE_KEYSTORE_FILE")).isFile) throw GradleException("Release signing is not configured (missing: ${missing.ifEmpty { listOf("RELEASE_KEYSTORE_FILE (not a file)") }.joinToString()}). See README, Release signing.")
    }
}

