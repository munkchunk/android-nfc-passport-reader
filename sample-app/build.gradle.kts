plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.munkchunk.passportreader.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.munkchunk.passportreader.sample"
        minSdk = 29
        targetSdk = 36
        // From the shared VERSION_NAME: 0.1.0 is 100, 1.2.3 is 10203. A suffix
        // such as -SNAPSHOT is ignored, so it shares its release's code.
        versionName = property("VERSION_NAME").toString()
        val (major, minor, patch) = requireNotNull(
            Regex("""(\d+)\.(\d+)\.(\d+)(-.+)?""").matchEntire(versionName!!)
        ) { "VERSION_NAME must be MAJOR.MINOR.PATCH, optionally with a -suffix: $versionName" }
            .destructured.toList().take(3).map(String::toInt)
        require(minor < 100 && patch < 100) { "VERSION_NAME minor and patch must be below 100: $versionName" }
        versionCode = major * 10_000 + minor * 100 + patch

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key lives outside the repository, and these properties come
    // from the publisher's ~/.gradle/gradle.properties. Without them a release
    // build is left unsigned, so it still builds anywhere. See docs/releasing.md.
    val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            // Phones with NFC are ARM. The x86 libraries only serve emulators,
            // which cannot read a passport, and they are a third of the APK.
            // Debug builds keep every ABI, so the app still runs on one.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/NOTICE.md",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "module-info.class",
                "**/module-info.class",
                "META-INF/versions/9/module-info.class",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    implementation(project(":passport-reader"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)

    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    // Camera + on-device OCR. The bundled text-recognition model adds ~4MB but
    // works offline with no Play Services dependency and no first-run download.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.accompanist.permissions)

    testImplementation(libs.junit)
}
