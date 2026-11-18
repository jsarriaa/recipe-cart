plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
}

// Explicit module folders: a "**" glob would walk build/ directories while other tasks write them.
val moduleDirs = listOf("app", "core/*", "provider/*", "build-logic/convention")

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    kotlin {
        target(moduleDirs.map { "$it/src/**/*.kt" })
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target(listOf("*.gradle.kts", "build-logic/*.gradle.kts") + moduleDirs.map { "$it/*.gradle.kts" })
        ktlint(ktlintVersion)
    }
}
