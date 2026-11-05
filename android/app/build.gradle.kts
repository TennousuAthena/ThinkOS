import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ffmpegEnabled = providers.gradleProperty("thinkos.ffmpeg").orNull != "false"
val fairplayEnabled = providers.gradleProperty("thinkos.fairplay").orNull != "false"
// Where the corresponding source of this exact build is offered (GPLv3); shown on the legal screen.
val sourceCodeUrl = providers.gradleProperty("thinkos.sourceUrl").orElse("https://cnb.cool/kiwimoe/ThinkOS").get()
require(Regex("https://[A-Za-z0-9._~/-]+").matches(sourceCodeUrl)) { "thinkos.sourceUrl must be a plain HTTPS URL" }
val registryFixtureProbe = providers.gradleProperty("registryFixtureProbe").orNull == "true"
val registryFixturePublic = providers.gradleProperty("registryFixturePublic")
val registryFixtureAssets = layout.buildDirectory.dir("generated/regFixture-assets")
// CALL-0 experiment only: never resolve a remote coordinate or a default app dependency.
val callProbeVariant = providers.gradleProperty("callProbeVariant").orNull
val callProbeAar = providers.gradleProperty("callProbeAar").orNull
require((callProbeVariant == null) == (callProbeAar == null)) { "CALL-0 requires both callProbeVariant and callProbeAar" }
val callProbeEnabled = callProbeVariant != null
require(!callProbeEnabled || registryFixtureProbe) { "CALL-0 requires the independent regFixture target" }
require(!callProbeEnabled || callProbeVariant in setOf("android", "android-prefixed", "android-prefixed-stripped")) { "Select one fixed CALL-0 variant" }
val callProbeDirectory = layout.buildDirectory.dir("generated/call-probe")
val thinkosVersion = providers.gradleProperty("thinkos.version").get()
val versionParts = thinkosVersion.split('.')
require(versionParts.size == 3 && versionParts.all { it.matches(Regex("0|[1-9][0-9]*")) }) { "Invalid thinkos.version" }
val versionNumbers = versionParts.map { it.toLongOrNull() ?: error("thinkos.version overflow") }
require(versionNumbers[0] <= 2147 && versionNumbers[1] in 0..999 && versionNumbers[2] in 0..999) { "thinkos.version component overflow" }
val thinkosCode = versionNumbers[0] * 1_000_000 + versionNumbers[1] * 1000 + versionNumbers[2]
require(thinkosCode in 1..Int.MAX_VALUE.toLong()) { "thinkos.version code overflow" }

// Owner-managed and ignored by Git. Missing keys keep local debug/release
// interoperable; a present but incomplete configuration must fail closed.
val signingFile = rootProject.file("keystore.properties")
require(!signingFile.exists() || signingFile.isFile) { "keystore.properties must be a regular file" }
val ownerSigning = if (signingFile.isFile) Properties().apply {
    signingFile.inputStream().use { load(it) }
    require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !getProperty(it).isNullOrBlank() }) {
        "Incomplete keystore.properties (storeFile, storePassword, keyAlias, keyPassword required)"
    }
} else null
if (ownerSigning == null) logger.warn("ThinkOS: no keystore.properties; debug and release use the local debug certificate. Owner-run certificate migration is required before distribution.")

android {
    namespace = "moe.kiwi.thinkos"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "26.3.11579264"
    defaultConfig {
        applicationId = "moe.kiwi.thinkos"
        minSdk = 27
        targetSdk = 27
        versionCode = thinkosCode.toInt()
        versionName = thinkosVersion
        buildConfigField("boolean", "DISTRIBUTION_GPL", (ffmpegEnabled || fairplayEnabled).toString())
        buildConfigField("String", "SOURCE_CODE_URL", "\"$sourceCodeUrl\"")
        externalNativeBuild {
            cmake { arguments += "-DTHINKOS_FAIRPLAY=${if (fairplayEnabled) "ON" else "OFF"}" }
        }
        ndk { abiFilters += "armeabi-v7a" }
        testInstrumentationRunner = if (registryFixtureProbe || providers.gradleProperty("updateProbe").orNull == "true")
            "moe.kiwi.thinkos.UpdateInstrumentation" else if (providers.gradleProperty("tileProbe").orNull == "true")
            "moe.kiwi.thinkos.TileInstrumentation" else if (providers.gradleProperty("galleryGestureProbe").orNull == "true")
            "moe.kiwi.thinkos.GallerySwipeInstrumentation" else "android.test.InstrumentationTestRunner"
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (ownerSigning != null) create("thinkos") {
            storeFile = rootProject.file(ownerSigning.getProperty("storeFile"))
            require(storeFile!!.isFile) { "The configured signing keystore is unavailable" }
            storePassword = ownerSigning.getProperty("storePassword")
            keyAlias = ownerSigning.getProperty("keyAlias")
            keyPassword = ownerSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { if (ownerSigning != null) signingConfig = signingConfigs.getByName("thinkos") }
        create("regFixture") {
            initWith(getByName("debug"))
            isDebuggable = true
            applicationIdSuffix = ".regfixture"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (ownerSigning != null) "thinkos" else "debug")
        }
    }
    testBuildType = if (registryFixtureProbe) "regFixture" else "debug"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").assets.srcDir("build/generated/shared-assets")
    // initWith copies build configuration, not the debug source set.
    sourceSets.getByName("regFixture").apply {
        java.srcDir("src/debug/java")
        res.srcDir("src/debug/res")
        resources.srcDir("src/debug/resources")
        assets.srcDir("src/debug/assets")
        assets.srcDir(registryFixtureAssets)
    }
    sourceSets.getByName("test").java.srcDir("src/testSupport/java")
    sourceSets.getByName("androidTest").java.srcDir("src/testSupport/java")
    // Certificate-format probes belong to the test APK; general app trust stays system-only (OWN-18).
    sourceSets.getByName("androidTest").assets.srcDir("../../system/thinkos-certs/system/etc/security")
    sourceSets.getByName("androidTest").assets.srcDir("../../tools/tests/fixtures/certs-tls")
    sourceSets.getByName("androidTest").assets.srcDir("../../tools/tests/fixtures/ota")
    if (callProbeEnabled) sourceSets.getByName("androidTest").apply {
        manifest.srcFile("src/callProbe/AndroidManifest.xml")
        assets.srcDir(callProbeDirectory.map { it.dir("assets") })
    }
    testOptions {
        unitTests.all { it.systemProperty("tzdata.dir", file("../../shared/tzdata").absolutePath) }
    }
    lint {
        abortOnError = true
        // ADR-010: this dedicated API-27 device is never distributed through Play.
        disable += "ExpiredTargetSdkVersion"
    }
}

val prepareCallProbeAar by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    inputs.files("../../tools/call_probe_aar.py", "../../tools/call-probe-NOTICE.txt")
    inputs.property("variant", callProbeVariant ?: "disabled")
    inputs.property("localAar", callProbeAar ?: "disabled")
    // Only our O_NOFOLLOW/nonblocking bounded reader opens the untrusted input.
    // Do not ask Gradle's file fingerprinter to open a caller-supplied FIFO/path.
    outputs.upToDateWhen { false }
    outputs.file(callProbeDirectory.map { it.file("aar/webrtc-call-probe.aar") })
    outputs.file(callProbeDirectory.map { it.file("assets/call-probe/webrtc.json") })
    outputs.file(callProbeDirectory.map { it.file("assets/call-probe/NOTICE.txt") })
    doFirst {
        require(callProbeEnabled) { "No CALL-0 AAR selected" }
        commandLine("python3", "-I", "tools/call_probe_aar.py", "--aar", requireNotNull(callProbeAar),
            "--variant", requireNotNull(callProbeVariant), "--output", callProbeDirectory.get().asFile.absolutePath)
    }
}
if (callProbeEnabled) tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }
    .configureEach { dependsOn(prepareCallProbeAar) }

dependencies {
    if (callProbeEnabled) add("androidTestImplementation",
        files(callProbeDirectory.map { it.file("aar/webrtc-call-probe.aar") }).builtBy(prepareCallProbeAar))
    // Native code in this APK is restricted to armeabi-v7a.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Audio/video playback. Pure Java (no native libraries) so the APK stays armv7-only.
    // 1.9.x is the newest line that builds against compileSdk 35; 1.10+ needs 36.
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.2")
    // Streaming protocols. Each AAR is pure Java (no jni/ directory), so the APK stays armv7-only.
    implementation("androidx.media3:media3-exoplayer-hls:1.9.2")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.2")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.9.2")
    // AC-3, E-AC-3, DTS and ALAC audio: the platform has no decoder for them. Jellyfin's prebuilt FFmpeg extension
    // ships an armeabi-v7a libffmpegJNI.so under GPL-3.0. The full APK includes it and is distributed under GPLv3 (ADR-043).
    // -Pthinkos.ffmpeg=false omits it; unsupported audio then produces an explicit "cannot decode" message.
    if (ffmpegEnabled) {
        implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val generatePluginSchema by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("python3", "shared/gen/gen_kotlin.py")
    inputs.dir("../../shared/schema/plugin")
    inputs.dir("../../shared/schema/update")
    inputs.dir("../../shared/schema/registry")
    inputs.dir("../../shared/gen")
    inputs.file("../../shared/schema/settings/system.json")
    outputs.file("src/main/java/moe/kiwi/thinkos/plugin/PluginSchema.kt")
    outputs.file("src/main/java/moe/kiwi/thinkos/update/UpdateModels.kt")
    outputs.file("src/main/java/moe/kiwi/thinkos/update/RegistryModels.kt")
}
val packagePlugins by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("python3", "tools/package_plugins.py", "--output", "android/app/src/main/assets/plugins")
    inputs.dir("../../plugins")
    inputs.dir("../../shared/schema/plugin")
    inputs.file("../../tools/package_plugins.py")
    inputs.file("../../shared/gen/schema_tools.py")
    inputs.file("../../shared/schema/settings/system.json")
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
// Keep the APK's legal texts byte-for-byte identical to the repository originals.
val syncLegal by tasks.registering(Sync::class) {
    from("../..") { include("LICENSE", "LICENSE-GPL-3.0.md", "LICENSE-APACHE-2.0.txt", "LICENSE-MPL-2.0.txt", "THIRD_PARTY_NOTICES.md") }
    into("build/generated/shared-assets/legal")
}
// Reserved fixture trust must never be packaged by normal debug/release.
val checkRegistryFixtureTrustIsolation by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("python3", "-I", "tools/registry_fixture_assets.py", "--check-production")
}
val generateRegistryFixtureAssets by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    inputs.files("../../tools/registry_fixture_assets.py", "../../tools/release_tools.py")
    inputs.property("registryFixturePublic", registryFixturePublic.orElse(""))
    inputs.property("registryFixtureProbe", registryFixtureProbe)
    registryFixturePublic.orNull?.let { inputs.file(it) }
    outputs.file(registryFixtureAssets.map { it.file("update/keys.json") })
    doFirst {
        require(registryFixtureProbe) { "regFixture requires -PregistryFixtureProbe=true" }
        val publicFile = registryFixturePublic.orNull
            ?: error("regFixture requires -PregistryFixturePublic=/absolute/outside/repository/public/keys.json")
        commandLine("python3", "-I", "tools/registry_fixture_assets.py", "--public", publicFile,
            "--output", registryFixtureAssets.get().asFile.absolutePath)
    }
}
tasks.matching { it.name == "mergeRegFixtureAssets" }.configureEach { dependsOn(generateRegistryFixtureAssets) }
tasks.named("preBuild") {
    dependsOn(generatePluginSchema, packagePlugins, syncTzdata, syncSettingsSchema, syncLegal, checkRegistryFixtureTrustIsolation)
}
