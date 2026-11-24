import java.util.Properties

plugins {
    alias(libs.plugins.cpl.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing is read from the gitignored keystore.properties (see docs/DEVICE_SETUP.md).
// Without it, the release build is produced unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use(::load)
    }

android {
    namespace = "com.lechugones.cocina"

    defaultConfig {
        // Never change once real data exists (CLAUDE.md, hard rule 6).
        applicationId = "com.lechugones.cocina"
        versionCode = 14
        versionName = "1.4.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // The release build (R8, resource shrinking) installed as the debug app: same package and
        // debug key, so it can be tested on the phone over the debug app, keeping its data, without
        // touching the owner's release app.
        create("minified") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-min"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        checkDependencies = true
        abortOnError = true
        // Version-bump reminders are handled when a phase starts, not by lint. targetSdk stays at
        // the test phone's API level (36) so runtime behaviour can be verified on the device.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":provider:mercadona"))
    implementation(project(":provider:dia"))
    implementation(project(":provider:carrefour"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    // Recipe photos (SPEC § 4.9) and product images from the shops' CDNs, over the shared OkHttp client.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Plain JVM tests of UI formatting that does not need Android (AC6.2).
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}
