plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}
kotlin {
    jvmToolchain(21)
    jvm("desktop")
    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.desktop.currentOs)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit.jupiter)
            }
        }
    }
}
compose.desktop { application { mainClass = "io.github.saldraykee22.popi.desktop.MainKt" } }
tasks.withType<Test>().configureEach { useJUnitPlatform() }
tasks.register("test") {
    group = "verification"
    description = "Runs the desktop JVM test suite."
    dependsOn("desktopTest")
}
