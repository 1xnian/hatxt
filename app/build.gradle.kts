plugins {
    alias(libs.plugins.homeassistant.android.application)
    alias(libs.plugins.homeassistant.android.flavor)
    alias(libs.plugins.homeassistant.android.dependencies)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    useLibrary("android.car")

    defaultConfig {
        manifestPlaceholders["sentryRelease"] = "$applicationId@$versionName"
        manifestPlaceholders["sentryDsn"] = System.getenv("SENTRY_DSN") ?: ""

        testInstrumentationRunner = "io.homeassistant.companion.android.util.HAAndroidJUnitRunner"

        // Only ship the arm64-v8a native libraries. libmicrowakeword.so + libcronet are ~19 MB per
        // ABI, so packing all four ABIs bloats any APK to ~73 MB while only arm64 phones are ever
        // targeted. Applied to the defaultConfig so every build type benefits, including release.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        bundle {
            language {
                // We want to keep the translations in the final AAB for all the language
                enableSplit = false
            }
        }
    }

    buildTypes {
        debug {
            // Required for HWASan wrap.sh to be included uncompressed in the APK
            // See https://developer.android.com/ndk/guides/hwasan
            packaging {
                jniLibs {
                    useLegacyPackaging = true
                }
            }
            // (see ndk.abiFilters in defaultConfig for the ABI selection)
        }

        named("release") {
            // R8 code shrinking IS enabled here. It was toggled off once out of an (incorrect) fear
            // that an empty app/proguard-rules.pro would strip every library class and crash the app
            // with NoClassDefFoundError. An on-device install of the resulting 18.4 MiB APK proved
            // that wrong: R8 keeps everything reachable from the manifest plus each library's own
            // consumer keep rules, so the app runs fine. The empty template is kept on purpose — do
            // not add broad keep rules unless a specific feature is later found to break after
            // shrinking. This brings the APK from ~48 MB (unminified) to ~18 MB, well under the
            // official 46.4 MB minimal release.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        // Variant kept for scripts/tasks that still ask for `debugArm64` by name. It is now
        // identical to `debug`: the arm64-only ABI filter was promoted to the defaultConfig above,
        // so every build type (including `debug` and `release`) ships arm64-v8a only.
        create("debugArm64") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    lint {
        // Until we fully migrate to Material3 this lint issue is too verbose https://github.com/home-assistant/android/issues/5420
        disable += listOf("UsingMaterialAndMaterial3Libraries")
    }
}

dependencies {
    // Most of the dependencies are coming from the convention plugin to avoid duplication with `:automotive` module.
    "fullImplementation"(libs.car.projected)
    ksp(project(":provides-sensor-processor"))
}
