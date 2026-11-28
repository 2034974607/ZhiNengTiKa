import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.zhinengtika.nativeapp"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    signingConfigs {
        val keystorePath = providers.environmentVariable("ZHINENGTIKA_KEYSTORE").orNull
        if (keystorePath != null) {
            create("nativeRelease") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("ZHINENGTIKA_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ZHINENGTIKA_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ZHINENGTIKA_KEY_PASSWORD").get()
            }
        }
    }

    defaultConfig {
        applicationId = "app.zhinengtika.nativeapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 10003
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "新教育智能题卡·调试")
        }
        release {
            signingConfig = signingConfigs.findByName("nativeRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")

    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Espresso 3.7 obtains InputManager through Context on modern Android.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

tasks.register("writeDependencyInventory") {
    group = "verification"
    description = "Record exact release runtime artifact coordinates and SHA-256 digests"
    doLast {
        val artifacts = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val rows = artifacts.sortedBy { it.moduleVersion.id.toString() + it.name }.map { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            listOf(artifact.moduleVersion.id.toString(), artifact.type, artifact.file.name, sha256).joinToString("\t")
        }
        val report = layout.buildDirectory.file("reports/dependency-inventory.tsv").get().asFile
        report.parentFile.mkdirs()
        report.writeText("coordinate\ttype\tfilename\tsha256\n" + rows.joinToString("\n") + "\n")
        println("Release dependency inventory: ${report.absolutePath}")
    }
}
