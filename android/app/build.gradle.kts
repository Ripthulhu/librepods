import java.util.Properties
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory

val appVersionName = (rootProject.extra["appVersionName"] as String) + "-pixel167"
val appVersionCode = 200000252

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.room3)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.detekt)
//    alias(libs.plugins.hilt)
    id("kotlin-parcelize")
}

abstract class EldAssetsSync : Sync() {
    @get:OutputDirectory
    abstract val assetsDirectory: DirectoryProperty
}

val localPropsFile = rootProject.file("local.properties")
val props = Properties().apply {
    if (localPropsFile.exists()) {
        load(localPropsFile.inputStream())
    }
}

val releaseSigningAvailable = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD"
).all { props[it]?.toString()?.isNotBlank() == true }

room3 {
    schemaDirectory("$projectDir/schemas")
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java")
                create("kotlin")
            }
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "kotlin.uuid.ExperimentalUuidApi",
            "kotlinx.coroutines.FlowPreview",
            "kotlinx.serialization.ExperimentalSerializationApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi",
            "com.google.accompanist.permissions.ExperimentalPermissionsApi"
        )
    }
}

android {
    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(props["RELEASE_STORE_FILE"] as String)
                storePassword = props["RELEASE_STORE_PASSWORD"] as String
                keyAlias = props["RELEASE_KEY_ALIAS"] as String
                keyPassword = props["RELEASE_KEY_PASSWORD"] as String
            }
        }
    }
    namespace = "me.kavishdevar.librepods"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.kavishdevar.librepods"
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DLIBREPODS_PRIVATE_OWNED_FDK=ON",
                    "-DLIBREPODS_PRIVATE_WIDE_PCM=ON",
                    "-DELD_KEEP_GAME_FLUSH_BASELINE=ON",
                    "-DELD_KEEP_GAME_PACKET_BASELINE=ON",
                    "-DLIBREPODS_NATIVE_TRACE=OFF",
                    "-DLIBREPODS_ELD_ASSETS_DIR=${layout.projectDirectory.dir(".cxx/eld-assets").asFile.invariantSeparatorsPath}"
                )
            }
        }
    }
    buildTypes {
        release {
            buildConfigField("boolean", "PRIVATE_GAME_KEEP_FLUSH_BASELINE", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            defaultConfig {
                minSdk = 33
            }
        }
        debug {
            buildConfigField("boolean", "PRIVATE_GAME_KEEP_FLUSH_BASELINE", "true")
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            versionNameSuffix = "-debug"
            defaultConfig {
                minSdk = 33
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        aidl = true
        compose = true
        viewBinding = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    lint {
        checkDependencies = false
        checkGeneratedSources = false
        // Existing issues are recorded in lint-baseline.xml; any new warning or error fails.
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        abortOnError = true
        // These depend on what is published upstream, not on the code, so they would fail
        // unchanged commits whenever a new library version appears.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    ndkVersion = "30.0.14904198"
}

val prepareEldAssets = tasks.register<EldAssetsSync>("prepareEldAssets") {
    val noticeStage = layout.projectDirectory.dir(".cxx/eld-assets")
    val notices = listOf("NOTICE-FDK.txt", "NOTICE-owned-fdk-MIT.txt", "NOTICE-FDK-modifications.txt")
        .map { noticeStage.file("aac-eld/$it").asFile }
    dependsOn(tasks.matching { it.name.startsWith("configureCMakeRelease[") })
    from(noticeStage) { include("aac-eld/**") }
    assetsDirectory.set(layout.buildDirectory.dir("generated/eld-assets"))
    into(assetsDirectory)
    inputs.files(notices)
    // Validate before Sync's NO-SOURCE shortcut so missing notices cannot produce an incomplete APK.
    onlyIf {
        for (notice in notices) {
            check(notice.isFile && notice.length() > 0) { "Missing encoder notice: $notice" }
        }
        true
    }
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareEldAssets, EldAssetsSync::assetsDirectory)
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.21.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.foundation.layout)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.annotations)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.aboutlibraries.compose)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.backdrop)
//    implementation(libs.hilt)
//    implementation(libs.hilt.compiler)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.protobuf.kotlin)
//    compileOnly(files("../../../framework-classes.jar"))
    implementation(libs.androidx.healthconnect.client)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    // Release has no sources of its own, so the debug variant covers everything; detektMain then
    // runs detektDebug, which reads detekt-baseline-debug.xml next to this file.
    ignoredBuildTypes = listOf("release")
    baseline = layout.projectDirectory.file("detekt-baseline.xml")
    basePath = rootProject.layout.projectDirectory
}

// Type resolution also needs the module's Java side (hand-written Java, AIDL stubs, BuildConfig,
// protobuf Java classes). The plugin only sets the Kotlin compile classpath as a convention, and
// adding to it replaces that convention, so both parts are listed here.
fun ConfigurableFileCollection.withDebugJavaClasses() = from(
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>("compileDebugKotlin").map { it.libraries },
    tasks.named<JavaCompile>("compileDebugJavaWithJavac").flatMap { it.destinationDirectory }
)
tasks.withType<dev.detekt.gradle.Detekt>().matching { it.name == "detektDebug" }.configureEach {
    classpath.withDebugJavaClasses()
}
tasks.withType<dev.detekt.gradle.DetektCreateBaselineTask>().matching { it.name == "detektBaselineDebug" }.configureEach {
    classpath.withDebugJavaClasses()
}

tasks.register("verifyPixel") {
    dependsOn(
        ":core:testDebugUnitTest", "testDebugUnitTest",
        ":core:lintDebug", "lintDebug",
        ":core:detektMain", "detektMain",
        "assembleDebug", "assembleRelease"
    )
}

tasks.withType<Test>().configureEach {
    // Robolectric retains framework sandboxes across classes; bound each worker's lifetime and heap.
    maxHeapSize = "1g"
    forkEvery = 50
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
    )
}

aboutLibraries {
    export {
        prettyPrint = true
        excludeFields = listOf("generated")
        outputFile = file("src/main/res/raw/aboutlibraries.json")
    }
}

val releaseDir = rootProject.file("../release")

tasks.register<Copy>("collectReleaseArtifacts") {
    description = "Collects GitHub debug and release APKs into a single directory."

    dependsOn(
        "assembleDebug",
        "assembleRelease"
    )

    into(releaseDir)

    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename(
            ".*\\.apk",
            "LibrePods-v${appVersionName}-${appVersionCode}-release.apk"
        )
    }

    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        rename(
            ".*\\.apk",
            "LibrePods-v${appVersionName}-${appVersionCode}-debug.apk"
        )
    }
}
