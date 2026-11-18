plugins {
    `kotlin-dsl`
}

group = "com.lechugones.cocina.buildlogic"

dependencies {
    compileOnly(libs.android.gradleApiPlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "cpl.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "cpl.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "cpl.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
