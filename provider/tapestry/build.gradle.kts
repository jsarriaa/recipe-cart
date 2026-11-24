plugins {
    alias(libs.plugins.cpl.jvm.library)
}

dependencies {
    api(project(":provider:api"))
    // Eroski and Caprabo answer HTML fragments wrapped in JSON (docs/providers/eroski.md).
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
