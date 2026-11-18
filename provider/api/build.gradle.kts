plugins {
    alias(libs.plugins.cpl.jvm.library)
}

dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.coroutines.core)
    // Shared HTTP error handling for providers (docs/providers/README.md § Etiquette).
    api(libs.retrofit)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
