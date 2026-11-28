plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.19.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

tasks.register<JavaExec>("verifyOfflineLibrary") {
    group = "verification"
    description = "Import and reopen the supplied 519-card archive, including legacy migration and repeat import"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("app.zhinengtika.nativeapp.data.OfflineLibraryVerificationKt")
    val archive = providers.gradleProperty("offlineLibraryZip")
    doFirst {
        require(archive.isPresent) { "Pass -PofflineLibraryZip=/path/to/library.zip" }
        args(archive.get(), layout.buildDirectory.file("reports/offline-library-verification.json").get().asFile.absolutePath)
    }
}

tasks.register<JavaExec>("benchmarkSynthetic") {
    group = "verification"
    description = "Measure synthetic host JVM data paths; not Android startup, frames, or device performance"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("app.zhinengtika.nativeapp.data.ProtocolBenchmarkKt")
    args(layout.buildDirectory.file("reports/synthetic-benchmark.json").get().asFile.absolutePath)
}
