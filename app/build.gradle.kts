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
            // Only ship the arm64-v8a native libraries. The bundled Cronet and microwakeword
            // native libs are ~19 MB per ABI; packing all four ABIs bloats the debug APK to
            // ~73 MB while only arm64 devices (e.g. the phone this build targets) are ever used.
            // This cuts the APK to ~20 MB without changing release behaviour.
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }

        // Arm64-only debug variant for on-device testing on arm64 phones (e.g. Redmi K70 Ultra).
        // The default `minimalDebug` build stays universal (all 4 ABIs) and is untouched;
        // this variant only drops the other ABIs to keep the test APK small
        // (libmicrowakeword.so + libcronet account for ~78 MB across 4 ABIs).
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
