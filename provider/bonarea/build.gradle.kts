plugins {
    alias(libs.plugins.cpl.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":provider:api"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
