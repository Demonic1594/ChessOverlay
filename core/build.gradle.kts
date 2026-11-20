// Chess rules, UCI parsing, board tracking and the overlay's layout: plain
// Kotlin with no Android in it, so it is tested (and the overlay previewed)
// on an ordinary JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Previews are written here: core/build/preview/*.png
    systemProperty("preview.dir", layout.buildDirectory.dir("preview").get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}
