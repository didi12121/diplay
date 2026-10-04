/*
 * carlife-provider — open CarLife (Baidu CarLife+ / CarLife V2.0) wired AOA
 * provider for the DiPlay multi-projection host.
 *
 * Source origin: github.com/fgrcwp/apollo-DuerOS
 *   CarLife-Android-Vehicle-V2.0/carlife-sdk (Apache License 2.0)
 * Original implementation: Baidu Apollo-DuerOS public CarLife source.
 * See NOTICE / LICENSE in this module root for attribution requirements.
 *
 * UNLIKE the rest of DiPlay, this module keeps the original
 * `com.baidu.carlife.*` package names and file layout so upstream provenance
 * stays reviewable. Do NOT reformat or rename these files casually.
 */
plugins {
    id("com.android.library")
    id("com.google.protobuf") version "0.10.0"
}

android {
    namespace = "com.baidu.carlife.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 28

        buildConfigField("String", "CARLIFE_SDK_VERSION", "\"2.0.0-open\"")
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }


    sourceSets {
        getByName("main") {
            // Original layout: AIDL lives beside the sources.
            java.srcDir("src/main/java")
        }
    }

    lint {
        abortOnError = false
        // Upstream baseline: Bluetooth permission errors live in the wireless /
        // instant-pairing code paths, which the USB-AOA probe never executes
        // (wireless CarLife is out of scope). Re-review if wireless is added.
        disable += "MissingPermission"
    }
}

kotlin {
    compilerOptions {
        // Upstream compiled with -Xjvm-default=all (interfaces called from
        // Java). Keep the same bytecode shape after dropping @JvmDefault
        // (deprecated-error under Kotlin 2.x).
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    // DiPlay projection abstraction (ProjectionBackend / ProjectionResource...).
    api(project(":shared"))
    // Upstream binary helper (com.baidu.encryption.EncryptionUtils), shipped in
    // the Apache-2.0 upstream repository and attributed in NOTICE.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    // protobuf-lite: matches upstream's javalite codegen (proto2 supported).
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    implementation("com.squareup.okhttp3:okhttp:3.14.9")

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}