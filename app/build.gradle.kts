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
            // R8 is intentionally NOT enabled here.
            //
            // It was tried (combined with the single-ABI filter) and produced a 18.4 MiB APK, but the
            // result was unusable: with no keep rules in this repository (app/proguard-rules.pro is
            // still the untouched Android Studio template) almost every application and library class
            // was stripped — only classes referenced from the manifest survived. The numbers speak for
            // themselves ("androidx/compose" occurrences in the DEX went 58,506 -> 60, "retrofit" 350
            // -> 0, "dagger" 1,399 -> 1), so the app crashed immediately with NoClassDefFoundError.
            //
            // The upstream project ships its releases unminified too, which is why the official
            // app-minimal-release.apk is 46.4 MB. Enabling R8 here properly would mean building and
            // validating a full keep-rule set first; don't flip this on without doing that.
            isMinifyEnabled = false
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
