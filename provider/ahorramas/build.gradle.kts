plugins {
    alias(libs.plugins.cpl.jvm.library)
}

dependencies {
    api(project(":provider:api"))
    // Ahorramás answers HTML tiles only (docs/providers/ahorramas.md).
    implementation(libs.jsoup)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
