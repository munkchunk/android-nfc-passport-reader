plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
}

android {
    namespace = "io.github.munkchunk.passportreader"
    compileSdk = 35

    defaultConfig {
        minSdk = 29

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "17"
        }
    }

    // Load-bearing: BouncyCastle and JMRTD ship colliding META-INF entries.
    // Packaging fails without these excludes.
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

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.multidex)

    // JPEG 2000 decoder for chip images: OpenJPEG behind a JNI wrapper,
    // BSD-2 throughout. Chosen over JJ2000, whose licence grants no rights
    // for non-conforming products. See NOTICE.md.
    implementation(libs.jp2.android)

    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcutil)

    // These three bundle their own crypto provider; excluding it avoids
    // duplicate BouncyCastle/SpongyCastle classes at dex time.
    //
    // implementation, not api: no JMRTD or SCUBA type appears in this
    // library's public surface, so consumers do not compile against them.
    // mapping/DocumentMappers.kt and mapping/VerificationMappers.kt are where
    // their types stop. Putting one on a public data class would force these
    // back to api and put an LGPL library on every consumer's compile
    // classpath -- see NOTICE.md, "LGPL dependencies".
    implementation(libs.jmrtd) {
        exclude(group = "org.bouncycastle")
        exclude(group = "com.madgag.spongycastle")
    }

    implementation(libs.scuba.sc.android) {
        exclude(group = "org.bouncycastle")
        exclude(group = "com.madgag.spongycastle")
    }

    implementation(libs.cert.cvc) {
        exclude(group = "org.bouncycastle")
        exclude(group = "com.madgag.spongycastle")
    }

    // WSQ biometric image support
    implementation(libs.jnbis)

    implementation(libs.commons.codec)


    implementation(libs.androidannotations.api)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

configurations.all {
    resolutionStrategy {
        force("org.bouncycastle:bcprov-jdk18on:${libs.versions.bouncycastle.get()}")
        force("org.bouncycastle:bcpkix-jdk18on:${libs.versions.bouncycastle.get()}")
        force("org.bouncycastle:bcutil-jdk18on:${libs.versions.bouncycastle.get()}")
    }
}
