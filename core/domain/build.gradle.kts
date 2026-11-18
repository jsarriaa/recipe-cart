plugins {
    alias(libs.plugins.cpl.jvm.library)
}

dependencies {
    // YAML front matter of recipe packs (docs/RECIPE_FORMAT.md). Pure Kotlin, safe on Android 8+.
    implementation(libs.kaml)
}
