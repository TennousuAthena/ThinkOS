plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "moe.kiwi.thinkos"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "26.3.11579264"
    defaultConfig {
        applicationId = "moe.kiwi.thinkos"
        minSdk = 27
        targetSdk = 27
        versionCode = 2
        versionName = "0.1.1"
        ndk { abiFilters += "armeabi-v7a" }
        testInstrumentationRunner = if (providers.gradleProperty("tileProbe").orNull == "true")
            "moe.kiwi.thinkos.TileInstrumentation" else if (providers.gradleProperty("galleryGestureProbe").orNull == "true")
            "moe.kiwi.thinkos.GallerySwipeInstrumentation" else "android.test.InstrumentationTestRunner"
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").assets.srcDir("build/generated/shared-assets")
    testOptions {
        unitTests.all { it.systemProperty("tzdata.dir", file("../../shared/tzdata").absolutePath) }
    }
    lint {
        abortOnError = true
        // ADR-010: this dedicated API-27 device is never distributed through Play.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // Pure JVM/Android libraries; the only native library is our armv7 Lua runtime.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Audio/video playback. Pure Java (no native libraries) so the APK stays armv7-only.
    // 1.9.x is the newest line that builds against compileSdk 35; 1.10+ needs 36.
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val generatePluginSchema by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("python3", "shared/gen/gen_kotlin.py")
    inputs.dir("../../shared/schema/plugin")
    inputs.dir("../../shared/gen")
    outputs.file("src/main/java/moe/kiwi/thinkos/plugin/PluginSchema.kt")
}
val packagePlugins by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("python3", "tools/package_plugins.py", "--output", "android/app/src/main/assets/plugins")
    inputs.dir("../../plugins")
    inputs.dir("../../shared/schema/plugin")
    inputs.file("../../tools/package_plugins.py")
    outputs.dir("src/main/assets/plugins")
}
// The IANA database is carried in the APK because the ROM's copy is frozen (2021a on the device).
val syncTzdata by tasks.registering(Sync::class) {
    from("../../shared/tzdata") { include("zoneinfo/**", "zones.tsv", "links.tsv", "countries.tsv", "VERSION") }
    into("build/generated/shared-assets/tzdata")
}
// System-level settings declaration, shared with the Server.
val syncSettingsSchema by tasks.registering(Sync::class) {
    from("../../shared/schema/settings") { include("system.json") }
    into("build/generated/shared-assets/settings")
}
tasks.named("preBuild") { dependsOn(generatePluginSchema, packagePlugins, syncTzdata, syncSettingsSchema) }
