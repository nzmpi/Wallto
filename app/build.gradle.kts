plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nzm.wallet"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nzm.wallet"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/FastDoubleParser-*",
                "META-INF/io.netty.versions.properties",
                "META-INF/thirdparty-LICENSE",
                "META-INF/Schubfach-LICENSE"
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Crypto + RPC
    // web3j 6 pulls the AWS KMS SDK (with netty and an old jackson-core) for a signer this app never uses
    implementation(libs.web3j.core) { exclude(group = "software.amazon.awssdk") }
    constraints {
        // vertx (web3j -> tuweni) pulls jackson-core 2.21.5: GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89
        implementation(libs.jackson.core)
    }
    implementation(libs.bcprov.jdk18on)
    // Storage for chain configs
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Biometrics
    implementation(libs.androidx.biometric)
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    // BOM version: latest on https://github.com/reown-com/reown-kotlin/releases
    implementation(platform(libs.reown.android.bom))
    // a Reown dependency (kethereum) lists junit as a runtime dependency: exclude it on both paths
    implementation(libs.reown.android.core) { exclude(group = "junit") }
    implementation(libs.reown.walletkit) { exclude(group = "junit") }
    implementation(libs.play.services.code.scanner)
    implementation(libs.core)

    testImplementation(libs.junit)
    // real org.json: the Android stub throws in plain JVM tests
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}