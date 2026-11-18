import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** Pure Kotlin (JVM) module tested with JUnit Jupiter and kotlin.test, checked by Android lint. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            // Lets Android lint analyse pure Kotlin modules too (checkDependencies in :app).
            pluginManager.apply("com.android.lint")
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JAVA_VERSION
                targetCompatibility = JAVA_VERSION
            }
            configureKotlinCompilation()
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
            }
            dependencies {
                add("testImplementation", platform(libs.library("junit-bom")))
                add("testImplementation", libs.library("junit-jupiter"))
                add("testImplementation", libs.library("kotlin-test"))
                add("testRuntimeOnly", libs.library("junit-platform-launcher"))
            }
        }
    }
}
