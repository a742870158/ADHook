import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release scripts/CI pass an explicit version name. Local builds keep the existing timestamp convention.
val buildEpochMillis = providers.environmentVariable("SOURCE_DATE_EPOCH").orNull
    ?.toLongOrNull()?.times(1000L) ?: System.currentTimeMillis()
val configuredVersionCode = providers.gradleProperty("versionCode").orNull
    ?: providers.environmentVariable("ADHOOK_VERSION_CODE").orNull
val appVersionCode = configuredVersionCode?.toIntOrNull()
    ?: if (configuredVersionCode != null) error("versionCode must be a positive integer")
    else (buildEpochMillis / 60_000L).toInt()
require(appVersionCode > 0) { "versionCode must be a positive integer" }
val appVersionName = providers.gradleProperty("versionName").orNull
    ?: providers.environmentVariable("ADHOOK_VERSION_NAME").orNull
    ?: ("1.0." + SimpleDateFormat("yyMMdd.HHmm", Locale.CHINA).format(Date(buildEpochMillis)))

// Release signing comes from the GitHub Actions environment or a local, gitignored
// signing.properties. Local builds may use the same formal signing identity for device
// verification, but the credential and the keystore never enter Git.
val localSigningProperties = rootProject.file("signing.properties")
val localSigning = Properties().apply {
    if (localSigningProperties.isFile) {
        localSigningProperties.inputStream().use { stream -> load(stream) }
    }
}
fun signingValue(envName: String, propertyName: String): String? =
    providers.environmentVariable(envName).orNull?.takeIf { it.isNotBlank() }
        ?: localSigning.getProperty(propertyName)?.takeIf { it.isNotBlank() }
val signingFile = signingValue("KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }
val signingStorePassword = signingValue("STORE_PASSWORD", "storePassword")
val signingAlias = signingValue("KEY_ALIAS", "keyAlias")
val signingKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")
val signingCertificateSha256 = signingValue("SIGNING_CERT_SHA256", "certificateSha256")

val validateReleaseCredentials = tasks.register("validateReleaseCredentials") {
    group = "verification"
    description = "Fail closed when release credentials or the expected signing identity are missing."
    doLast {
        val missing = listOf(
            "KEYSTORE_FILE/storeFile" to signingFile?.path,
            "STORE_PASSWORD/storePassword" to signingStorePassword,
            "KEY_ALIAS/keyAlias" to signingAlias,
            "KEY_PASSWORD/keyPassword" to signingKeyPassword,
            "SIGNING_CERT_SHA256/certificateSha256" to signingCertificateSha256
        ).filter { it.second.isNullOrBlank() }.map { it.first }
        check(missing.isEmpty()) {
            "Missing release signing settings: ${missing.joinToString()}. " +
                "Set them through GitHub Actions Secrets or a local, untracked signing.properties."
        }
        val file = checkNotNull(signingFile)
        check(file.isFile) { "Release keystore file is missing. No debug signing fallback is allowed." }
        val expected = checkNotNull(signingCertificateSha256).replace(":", "").replace(" ", "").uppercase(Locale.ROOT)
        check(expected.matches(Regex("[0-9A-F]{64}"))) { "Expected signing certificate must be a SHA-256 fingerprint." }
        val store = try {
            KeyStore.getInstance(file, checkNotNull(signingStorePassword).toCharArray())
        } catch (_: Exception) {
            error("Unable to open release keystore with the supplied credentials.")
        }
        val certificate = store.getCertificate(signingAlias)
            ?: error("The configured signing alias has no certificate.")
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }
        check(actual == expected) { "Release signing certificate does not match the pinned identity." }
        val key = try {
            store.getKey(signingAlias, checkNotNull(signingKeyPassword).toCharArray())
        } catch (_: Exception) {
            error("Unable to access the configured signing key.")
        }
        check(key != null) { "The configured alias is not a signing key." }
        logger.lifecycle("Release signing credentials validated against the pinned certificate.")
    }
}

android {
    namespace = "com.adhook"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adhook"
        // Product support starts at Android 12; service101 itself requires at least API26.
        minSdk = 31
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            // Java package roots must remain at src for Lint; tests live outside this tree.
            java.setSrcDirs(listOf("src"))
            kotlin.setSrcDirs(listOf("src"))
            res.srcDirs("res")
            assets.srcDirs("assets")
            resources.srcDirs("resources")
        }
        getByName("test") {
            java.setSrcDirs(listOf("tests/java"))
            kotlin.setSrcDirs(listOf("tests/java"))
            // 真实订阅样本等测试资源；含大文件时由 .gitignore 管理是否入库
            resources.srcDirs("tests/resources")
        }
    }

    buildFeatures {
        compose = true
        // Plugin runtime needs its own base versionCode to gate minBaseVersionCode.
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        create("release") {
            storeFile = signingFile
            storePassword = signingStorePassword
            keyAlias = signingAlias
            keyPassword = signingKeyPassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro",
                "plugin-abi-keep.pro")
        }
        debug {
            // A clean checkout has no release identity and builds with the default debug key.
            // CI/local release builds are signed by the release workflow only.
            if (signingFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
    lint {
        checkReleaseBuilds = true
        abortOnError = true
        // Xposed's package discovery is intentional; other correctness/security checks remain enforced.
        disable += "QueryAllPackagesPermission"
    }
}

tasks.matching { it.name == "validateSigningRelease" }
    .configureEach { dependsOn(validateReleaseCredentials) }

// Local release packaging must use the pinned formal certificate; no debug/unsigned fallback.
tasks.matching { it.name == "assembleRelease" }
    .configureEach { dependsOn(validateReleaseCredentials) }

tasks.withType<Test>().configureEach {
    // Robolectric downloads its SDK jar lazily; use the same public mirror as Gradle
    // dependencies so clean CI runners do not depend on Maven Central reachability.
    systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

dependencies {
    // Only the framework-provided API101 contract is on the production compile classpath.
    compileOnly(files("libs/libxposed-api-101.jar"))
    // App-side writable remote preferences; pinned to the existing hook API 101 generation.
    implementation("io.github.libxposed:service:101.0.0") {
        // Published service/interface101 classes are Java-only; their automatic Kotlin2.2 dependency
        // is unused. Keep the project's existing Kotlin runtime without forcing a version globally.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.json)
    testImplementation(files("libs/libxposed-api-101.jar"))
}

fun resolvePython(): String =
    providers.environmentVariable("ADHOOK_PYTHON").orNull?.takeIf { it.isNotBlank() } ?: "python"

val pluginAbiAllowlistFile = rootProject.file("tools/plugin_abi_allowlist.txt")
val pluginAbiClasses = pluginAbiAllowlistFile.takeIf { it.isFile }?.readLines()
    ?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { it.replace('.', '/') + ".class" }.orEmpty()
val compiledJavaClasses = layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")
val compiledKotlinClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debug")

/** Compile-time contract for host plugins; never bundled into the APK. */
val pluginApiJar = tasks.register<Jar>("pluginApiJar") {
    group = "build"
    description = "Build adhook-plugin-api-2.jar for host-plugin compilation (never packaged in the APK)."
    dependsOn("compileDebugJavaWithJavac")
    inputs.file(pluginAbiAllowlistFile)
    archiveFileName.set("adhook-plugin-api-2.jar")
    destinationDirectory.set(layout.buildDirectory.dir("plugin-sdk"))
    from(compiledJavaClasses) { include(pluginAbiClasses) }
    doFirst {
        check(pluginAbiClasses.isNotEmpty()) {
            "Plugin ABI allowlist is missing or empty: ${pluginAbiAllowlistFile.absolutePath}; run tools/plugin_abi.py generate"
        }
    }
}

/** Unminified base classes for host-plugin Robolectric tests (test-only artifact, never in the APK). */
val baseTestClassesJar = tasks.register<Jar>("baseTestClassesJar") {
    group = "build"
    description = "Build unminified base classes for plugin-repository host tests."
    dependsOn("compileDebugJavaWithJavac", "compileDebugKotlin")
    archiveFileName.set("adhook-test-classes-$appVersionName.jar")
    destinationDirectory.set(layout.buildDirectory.dir("plugin-sdk"))
    from(compiledJavaClasses)
    from(compiledKotlinClasses)
}

/** Fails when the checked-in ABI keep/allowlist no longer matches the compiled signatures. */
val verifyPluginAbiSurface = tasks.register<Exec>("verifyPluginAbiSurface") {
    group = "verification"
    description = "Verify the checked-in host-plugin ABI surface against compiled production classes."
    dependsOn("compileDebugJavaWithJavac", "compileDebugKotlin")
    inputs.dir(compiledJavaClasses)
    inputs.file(rootProject.file("tools/plugin_abi_allowlist.txt"))
    inputs.file(rootProject.file("app/plugin-abi-keep.pro"))
    workingDir(rootProject.projectDir)
    commandLine(resolvePython(), rootProject.file("tools/plugin_abi.py").absolutePath, "check",
        "--classes-dir", compiledJavaClasses.get().asFile.absolutePath,
        "--classes-dir", compiledKotlinClasses.get().asFile.absolutePath)
}

/** Fails when release R8 renamed or removed a host-plugin ABI class. */
val verifyPluginAbiMapping = tasks.register<Exec>("verifyPluginAbiMapping") {
    group = "verification"
    description = "Verify release R8 mapping keeps every host-plugin ABI class name unchanged."
    dependsOn("assembleRelease")
    val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    inputs.file(mapping)
    inputs.file(rootProject.file("tools/plugin_abi_allowlist.txt"))
    workingDir(rootProject.projectDir)
    doFirst {
        check(mapping.get().asFile.isFile) { "Expected R8 mapping is missing: ${mapping.get().asFile.name}" }
    }
    commandLine(resolvePython(), rootProject.file("tools/plugin_abi.py").absolutePath, "check-mapping",
        "--mapping", mapping.get().asFile.absolutePath)
}
