plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

android {
    namespace = "io.github.munkchunk.passportreader"
    compileSdk = 36

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
    // @StringRes on the error and read-state messages.
    implementation(libs.androidx.annotation)

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

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Published to Maven Central as io.github.munkchunk:passport-reader. Only the
// release variant is published, with sources and Dokka HTML as the javadoc
// jar, both of which Central requires. Signing reads the key from the
// publisher's ~/.gradle/gradle.properties, never from this repository; see
// docs/releasing.md. Without a key nothing is signed, so publishToMavenLocal
// works for anyone, and Central rejects an unsigned upload.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signing.keyId").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.munkchunk", "passport-reader", property("VERSION_NAME").toString())

    pom {
        name = "Android NFC Passport Reader"
        description = "Reads the contactless chip in an ePassport and verifies what can be trusted about it."
        url = "https://github.com/munkchunk/android-nfc-passport-reader"
        inceptionYear = "2026"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "munkchunk"
                name = "Iain Griffiths"
                url = "https://github.com/munkchunk"
            }
        }
        scm {
            url = "https://github.com/munkchunk/android-nfc-passport-reader"
            connection = "scm:git:https://github.com/munkchunk/android-nfc-passport-reader.git"
            developerConnection = "scm:git:ssh://git@github.com/munkchunk/android-nfc-passport-reader.git"
        }
    }
}

configurations.all {
    resolutionStrategy {
        force("org.bouncycastle:bcprov-jdk18on:${libs.versions.bouncycastle.get()}")
        force("org.bouncycastle:bcpkix-jdk18on:${libs.versions.bouncycastle.get()}")
        force("org.bouncycastle:bcutil-jdk18on:${libs.versions.bouncycastle.get()}")
    }
}
