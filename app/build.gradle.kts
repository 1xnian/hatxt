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
            // Enable R8 code shrinking: unused classes, methods and fields are stripped from the DEX
            // and the remaining ones are renamed. Together with the single-ABI filter this is what
            // brings the APK close to / below the size of the official releases. Debug is left
            // unminified so it stays readable and fast to iterate on.
            // Most libraries ship their own consumer keep rules; anything using reflection that is
            // not covered by them has to be whitelisted in app/proguard-rules.pro.
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
