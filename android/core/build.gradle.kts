plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.detekt)
    id("kotlin-parcelize")
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
            "kotlinx.coroutines.FlowPreview",
            "kotlinx.serialization.ExperimentalSerializationApi"
        )
    }
}

android {
    namespace = "me.kavishdevar.librepods.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    sourceSets {
        getByName("main") {
            res.directories += "src/main/res-apple"
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
        unitTests.isReturnDefaultValues = true
    }
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

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.protobuf.kotlin)
}
