# HA Android 动态地址（TXT/SRV）APK 静态检查报告

- 生成时间 (UTC)：2026-10-16T13:08:38Z
- 提交：`local`
- 工作流运行：`local`
- 运行器：`local` / `openjdk version "21.0.8" 2025-07-15 LTS`
- 检查的 APK：`app/build/outputs/apk/minimal/debug/app-minimal-debug.apk`
- 清单转储工具：`aapt`

## 1. 构建产物

```text
app/build/outputs/apk/minimal/debug/app-minimal-debug.apk (78817432 bytes)
app/build/outputs/apk/minimal/debug/output-metadata.json (464 bytes)
```

## 2. 源码中声明的 MAIN / LAUNCHER 入口

源码清单中不应出现 `Leaks` 入口；`Leaks` 只可能来自被合并的调试组件。

```text
app/src/main/AndroidManifest.xml:122:                <action android:name="android.intent.action.MAIN" />
app/src/main/AndroidManifest.xml:123:                <category android:name="android.intent.category.LAUNCHER" />
app/src/main/AndroidManifest.xml:127:                <action android:name="android.intent.action.MAIN" />
app/src/main/AndroidManifest.xml:128:                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
app/src/main/AndroidManifest.xml:143:                <action android:name="android.intent.action.MAIN" />
app/src/main/AndroidManifest.xml:145:                <category android:name="android.intent.category.HOME" />
```

## 3. LeakCanary 构建配置

`com.squareup.leakcanary:leakcanary-android` 不应再作为 `debugImplementation` 存在：它既会注入额外的 `Leaks` 启动入口，也是 `LockOutOfDateException` 的根因。

```text
gradle.properties:39:# `noLeakCanary` Gradle property that CI overrode with `-PnoLeakCanary=true`, while the dependency
gradle.properties:43:#   'com.squareup.leakcanary:shark:2.14' which is part of the dependency lock state
gradle.properties:46:# `leakcanary-android-instrumentation`, which never reaches the application APK. That also removes
gradle/libs.versions.toml:60:leakcanaryAndroid = "2.14"
gradle/libs.versions.toml:221:# `com.squareup.leakcanary:leakcanary-android` to `debugImplementation` is what used to inject the
gradle/libs.versions.toml:223:leakcanary-android-instrumentation = { module = "com.squareup.leakcanary:leakcanary-android-instrumentation", version.ref = "leakcanaryAndroid" }
build-logic/convention/src/main/kotlin/AndroidApplicationDependenciesConventionPlugin.kt:102:                    "androidTestImplementation"(libs.leakcanary.android.instrumentation)
```

## 4. 动态地址解析模块与测试是否进入工程

```text
-- 主实现 --
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/DnsMessageCodec.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/DohDnsClient.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointConnectGate.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointForegroundRefresher.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointModel.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointRefreshPolicy.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointRefreshWorker.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointRepository.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointRepositoryImpl.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointResolutionEngine.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointResolver.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointSettingsStore.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointStateMachine.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointUrlFactory.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/FallbackDnsClient.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/HttpsFallbackPolicy.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/OkHttpEndpointProbe.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/RawUdpDnsClient.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/ServerUrlSynchronizer.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/SrvRecordInterpreter.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/SystemDnsClient.kt
common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/TxtRecordInterpreter.kt
-- 单元测试 --
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/DnsMessageCodecTest.kt
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/DohResponseParserTest.kt
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointConnectGateTest.kt
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointRefreshPolicyTest.kt
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointResolverTest.kt
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointStateMachineTest.kt
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/EndpointUrlFactoryTest.kt
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/FallbackDnsClientTest.kt
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/HttpsFallbackPolicyTest.kt
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/RealDnsLookupTest.kt
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/SrvRecordInterpreterTest.kt
common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/TxtRecordInterpreterTest.kt
-- 统计：22 个实现文件，12 个测试文件
```

## 5. APK 摘要

```text
路径：app/build/outputs/apk/minimal/debug/app-minimal-debug.apk
大小：78817432 bytes
SHA-256：93211eb7deebe2778286af5442b20b95f29c9471045d3839cf14b4561abc218b
```

## 6. 包名 / 标签 / 启动 Activity（badging）

```text
package: name='io.homeassistant.companion.android.minimal.debug' versionCode='1' versionName='0.0.1-SNAPSHOT-minimal' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
sdkVersion:'23'
targetSdkVersion:'37'
application-label:'Home Assistant'
application-label-af:'Home Assistant'
application-label-am:'Home Assistant'
application-label-ar:'Home Assistant'
application-label-as:'Home Assistant'
application-label-az:'Home Assistant'
application-label-be:'Home Assistant'
application-label-bg:'Home Assistant'
application-label-bn:'Home Assistant'
application-label-bs:'Home Assistant'
application-label-ca:'Home Assistant'
application-label-cs:'Home Assistant'
application-label-da:'Home Assistant'
application-label-de:'Home Assistant'
application-label-el:'Home Assistant'
application-label-en-AU:'Home Assistant'
application-label-en-CA:'Home Assistant'
application-label-en-GB:'Home Assistant'
application-label-en-IN:'Home Assistant'
application-label-en-XC:'Home Assistant'
application-label-es:'Home Assistant'
application-label-es-419:'Home Assistant'
application-label-es-US:'Home Assistant'
application-label-et:'Home Assistant'
application-label-eu:'Home Assistant'
application-label-fa:'Home Assistant'
application-label-fi:'Home Assistant'
application-label-fr:'Home Assistant'
application-label-fr-CA:'Home Assistant'
application-label-gl:'Home Assistant'
application-label-gu:'Home Assistant'
application-label-he:'Home Assistant'
application-label-hi:'Home Assistant'
application-label-hr:'Home Assistant'
application-label-hu:'Home Assistant'
application-label-hy:'Home Assistant'
application-label-id:'Home Assistant'
application-label-in:'Home Assistant'
application-label-is:'Home Assistant'
application-label-it:'Home Assistant'
application-label-iw:'Home Assistant'
application-label-ja:'Home Assistant'
application-label-ka:'Home Assistant'
application-label-kk:'Home Assistant'
application-label-km:'Home Assistant'
application-label-kn:'Home Assistant'
application-label-ko:'Home Assistant'
application-label-ky:'Home Assistant'
application-label-lo:'Home Assistant'
application-label-lt:'Home Assistant'
application-label-lv:'Home Assistant'
application-label-mk:'Home Assistant'
application-label-ml:'Home Assistant'
application-label-mn:'Home Assistant'
application-label-mr:'Home Assistant'
application-label-ms:'Home Assistant'
application-label-my:'Home Assistant'
application-label-nb:'Home Assistant'
application-label-ne:'Home Assistant'
application-label-nl:'Home Assistant'
application-label-or:'Home Assistant'
application-label-pa:'Home Assistant'
application-label-pl:'Home Assistant'
application-label-pt:'Home Assistant'
application-label-pt-BR:'Home Assistant'
application-label-pt-PT:'Home Assistant'
application-label-ro:'Home Assistant'
application-label-ru:'Home Assistant'
application-label-si:'Home Assistant'
application-label-sk:'Home Assistant'
application-label-sl:'Home Assistant'
application-label-sq:'Home Assistant'
application-label-sr:'Home Assistant'
application-label-sr-Latn:'Home Assistant'
application-label-sv:'Home Assistant'
application-label-sw:'Home Assistant'
application-label-ta:'Home Assistant'
application-label-te:'Home Assistant'
application-label-th:'Home Assistant'
application-label-tl:'Home Assistant'
application-label-tr:'Home Assistant'
application-label-uk:'Home Assistant'
application-label-ur:'Home Assistant'
application-label-uz:'Home Assistant'
application-label-vi:'Home Assistant'
application-label-zh-CN:'Home Assistant'
application-label-zh-HK:'Home Assistant'
application-label-zh-TW:'Home Assistant'
application-label-zu:'Home Assistant'
application: label='Home Assistant' icon='res/mipmap-anydpi-v26/ic_launcher.xml' banner='res/mipmap-xhdpi-v4/ic_banner.png'
launchable-activity: name='io.homeassistant.companion.android.launch.LaunchActivity'  label='' icon=''
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
```

## 7. 合并后清单中的全部 MAIN / LAUNCHER 入口

```text
      A: android:name(0x01010003)="android.permission.ACCESS_NETWORK_STATE" (Raw: "android.permission.ACCESS_NETWORK_STATE")
      A: android:name(0x01010003)="android.permission.ACCESS_WIFI_STATE" (Raw: "android.permission.ACCESS_WIFI_STATE")
      A: android:name(0x01010003)="android.permission.ACCESS_LOCAL_NETWORK" (Raw: "android.permission.ACCESS_LOCAL_NETWORK")
      A: android:name(0x01010003)="android.permission.CHANGE_WIFI_MULTICAST_STATE" (Raw: "android.permission.CHANGE_WIFI_MULTICAST_STATE")
      A: android:name(0x01010003)="android.permission.BLUETOOTH" (Raw: "android.permission.BLUETOOTH")
      A: android:name(0x01010003)="android.permission.BLUETOOTH_ADMIN" (Raw: "android.permission.BLUETOOTH_ADMIN")
      A: android:name(0x01010003)="android.permission.BLUETOOTH_ADVERTISE" (Raw: "android.permission.BLUETOOTH_ADVERTISE")
      A: android:name(0x01010003)="android.permission.BLUETOOTH_CONNECT" (Raw: "android.permission.BLUETOOTH_CONNECT")
      A: android:name(0x01010003)="android.permission.BLUETOOTH_SCAN" (Raw: "android.permission.BLUETOOTH_SCAN")
      A: android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")
      A: android:name(0x01010003)="android.permission.VIBRATE" (Raw: "android.permission.VIBRATE")
      A: android:name(0x01010003)="android.permission.RECEIVE_BOOT_COMPLETED" (Raw: "android.permission.RECEIVE_BOOT_COMPLETED")
      A: android:name(0x01010003)="android.permission.ACCESS_FINE_LOCATION" (Raw: "android.permission.ACCESS_FINE_LOCATION")
      A: android:name(0x01010003)="android.permission.ACCESS_COARSE_LOCATION" (Raw: "android.permission.ACCESS_COARSE_LOCATION")
      A: android:name(0x01010003)="android.permission.ACCESS_BACKGROUND_LOCATION" (Raw: "android.permission.ACCESS_BACKGROUND_LOCATION")
      A: android:name(0x01010003)="android.permission.CAMERA" (Raw: "android.permission.CAMERA")
      A: android:name(0x01010003)="android.permission.CALL_PHONE" (Raw: "android.permission.CALL_PHONE")
      A: android:name(0x01010003)="android.permission.RECORD_AUDIO" (Raw: "android.permission.RECORD_AUDIO")
      A: android:name(0x01010003)="android.permission.MODIFY_AUDIO_SETTINGS" (Raw: "android.permission.MODIFY_AUDIO_SETTINGS")
      A: android:name(0x01010003)="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" (Raw: "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
      A: android:name(0x01010003)="android.permission.NFC" (Raw: "android.permission.NFC")
      A: android:name(0x01010003)="android.permission.ACTIVITY_RECOGNITION" (Raw: "android.permission.ACTIVITY_RECOGNITION")
      A: android:name(0x01010003)="com.google.android.gms.permission.ACTIVITY_RECOGNITION" (Raw: "com.google.android.gms.permission.ACTIVITY_RECOGNITION")
      A: android:name(0x01010003)="android.permission.ACCESS_NOTIFICATION_POLICY" (Raw: "android.permission.ACCESS_NOTIFICATION_POLICY")
      A: android:name(0x01010003)="android.permission.QUERY_ALL_PACKAGES" (Raw: "android.permission.QUERY_ALL_PACKAGES")
      A: android:name(0x01010003)="com.android.alarm.permission.SET_ALARM" (Raw: "com.android.alarm.permission.SET_ALARM")
      A: android:name(0x01010003)="android.permission.FOREGROUND_SERVICE" (Raw: "android.permission.FOREGROUND_SERVICE")
      A: android:name(0x01010003)="android.permission.FOREGROUND_SERVICE_DATA_SYNC" (Raw: "android.permission.FOREGROUND_SERVICE_DATA_SYNC")
      A: android:name(0x01010003)="android.permission.FOREGROUND_SERVICE_LOCATION" (Raw: "android.permission.FOREGROUND_SERVICE_LOCATION")
      A: android:name(0x01010003)="android.permission.FOREGROUND_SERVICE_MICROPHONE" (Raw: "android.permission.FOREGROUND_SERVICE_MICROPHONE")
      A: android:name(0x01010003)="android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING" (Raw: "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING")
      A: android:name(0x01010003)="android.permission.SYSTEM_ALERT_WINDOW" (Raw: "android.permission.SYSTEM_ALERT_WINDOW")
      A: android:name(0x01010003)="android.permission.MANAGE_ASSISTANT_AUDIO" (Raw: "android.permission.MANAGE_ASSISTANT_AUDIO")
      A: android:name(0x01010003)="android.permission.WRITE_EXTERNAL_STORAGE" (Raw: "android.permission.WRITE_EXTERNAL_STORAGE")
      A: android:name(0x01010003)="android.permission.WAKE_LOCK" (Raw: "android.permission.WAKE_LOCK")
      A: android:name(0x01010003)="android.permission.SCHEDULE_EXACT_ALARM" (Raw: "android.permission.SCHEDULE_EXACT_ALARM")
      A: android:name(0x01010003)="android.permission.PACKAGE_USAGE_STATS" (Raw: "android.permission.PACKAGE_USAGE_STATS")
      A: android:name(0x01010003)="android.permission.POST_NOTIFICATIONS" (Raw: "android.permission.POST_NOTIFICATIONS")
      A: android:name(0x01010003)="android.permission.POST_PROMOTED_NOTIFICATIONS" (Raw: "android.permission.POST_PROMOTED_NOTIFICATIONS")
      A: android:name(0x01010003)="android.permission.WRITE_SETTINGS" (Raw: "android.permission.WRITE_SETTINGS")
      A: android:name(0x01010003)="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" (Raw: "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND")
      A: android:name(0x01010003)="android.permission.health.READ_ACTIVE_CALORIES_BURNED" (Raw: "android.permission.health.READ_ACTIVE_CALORIES_BURNED")
      A: android:name(0x01010003)="android.permission.health.READ_BASAL_BODY_TEMPERATURE" (Raw: "android.permission.health.READ_BASAL_BODY_TEMPERATURE")
      A: android:name(0x01010003)="android.permission.health.READ_BASAL_METABOLIC_RATE" (Raw: "android.permission.health.READ_BASAL_METABOLIC_RATE")
      A: android:name(0x01010003)="android.permission.health.READ_BLOOD_GLUCOSE" (Raw: "android.permission.health.READ_BLOOD_GLUCOSE")
      A: android:name(0x01010003)="android.permission.health.READ_BLOOD_PRESSURE" (Raw: "android.permission.health.READ_BLOOD_PRESSURE")
      A: android:name(0x01010003)="android.permission.health.READ_BODY_FAT" (Raw: "android.permission.health.READ_BODY_FAT")
      A: android:name(0x01010003)="android.permission.health.READ_BODY_TEMPERATURE" (Raw: "android.permission.health.READ_BODY_TEMPERATURE")
      A: android:name(0x01010003)="android.permission.health.READ_BODY_WATER_MASS" (Raw: "android.permission.health.READ_BODY_WATER_MASS")
      A: android:name(0x01010003)="android.permission.health.READ_BONE_MASS" (Raw: "android.permission.health.READ_BONE_MASS")
      A: android:name(0x01010003)="android.permission.health.READ_DISTANCE" (Raw: "android.permission.health.READ_DISTANCE")
      A: android:name(0x01010003)="android.permission.health.READ_ELEVATION_GAINED" (Raw: "android.permission.health.READ_ELEVATION_GAINED")
      A: android:name(0x01010003)="android.permission.health.READ_FLOORS_CLIMBED" (Raw: "android.permission.health.READ_FLOORS_CLIMBED")
      A: android:name(0x01010003)="android.permission.health.READ_HEART_RATE" (Raw: "android.permission.health.READ_HEART_RATE")
      A: android:name(0x01010003)="android.permission.health.READ_HEART_RATE_VARIABILITY" (Raw: "android.permission.health.READ_HEART_RATE_VARIABILITY")
      A: android:name(0x01010003)="android.permission.health.READ_HEIGHT" (Raw: "android.permission.health.READ_HEIGHT")
      A: android:name(0x01010003)="android.permission.health.READ_HYDRATION" (Raw: "android.permission.health.READ_HYDRATION")
      A: android:name(0x01010003)="android.permission.health.READ_LEAN_BODY_MASS" (Raw: "android.permission.health.READ_LEAN_BODY_MASS")
      A: android:name(0x01010003)="android.permission.health.READ_OXYGEN_SATURATION" (Raw: "android.permission.health.READ_OXYGEN_SATURATION")
      A: android:name(0x01010003)="android.permission.health.READ_RESPIRATORY_RATE" (Raw: "android.permission.health.READ_RESPIRATORY_RATE")
      A: android:name(0x01010003)="android.permission.health.READ_RESTING_HEART_RATE" (Raw: "android.permission.health.READ_RESTING_HEART_RATE")
      A: android:name(0x01010003)="android.permission.health.READ_SLEEP" (Raw: "android.permission.health.READ_SLEEP")
      A: android:name(0x01010003)="android.permission.health.READ_STEPS" (Raw: "android.permission.health.READ_STEPS")
      A: android:name(0x01010003)="android.permission.health.READ_TOTAL_CALORIES_BURNED" (Raw: "android.permission.health.READ_TOTAL_CALORIES_BURNED")
      A: android:name(0x01010003)="android.permission.health.READ_VO2_MAX" (Raw: "android.permission.health.READ_VO2_MAX")
      A: android:name(0x01010003)="android.permission.health.READ_WEIGHT" (Raw: "android.permission.health.READ_WEIGHT")
      A: android:name(0x01010003)="android.hardware.touchscreen" (Raw: "android.hardware.touchscreen")
      A: android:name(0x01010003)="android.hardware.telephony" (Raw: "android.hardware.telephony")
      A: android:name(0x01010003)="android.hardware.camera" (Raw: "android.hardware.camera")
      A: android:name(0x01010003)="android.hardware.nfc" (Raw: "android.hardware.nfc")
      A: android:name(0x01010003)="android.hardware.location.gps" (Raw: "android.hardware.location.gps")
      A: android:name(0x01010003)="android.hardware.sensor" (Raw: "android.hardware.sensor")
      A: android:name(0x01010003)="android.hardware.microphone" (Raw: "android.hardware.microphone")
      A: android:name(0x01010003)="android.software.leanback" (Raw: "android.software.leanback")
      A: android:name(0x01010003)="android.hardware.bluetooth" (Raw: "android.hardware.bluetooth")
      A: android:name(0x01010003)="android.permission.READ_PHONE_STATE" (Raw: "android.permission.READ_PHONE_STATE")
      A: android:name(0x01010003)="android.permission.USE_BIOMETRIC" (Raw: "android.permission.USE_BIOMETRIC")
      A: android:name(0x01010003)="android.permission.USE_FINGERPRINT" (Raw: "android.permission.USE_FINGERPRINT")
        A: android:name(0x01010003)="androidx.car.app.connection.provider" (Raw: "androidx.car.app.connection.provider")
      A: android:name(0x01010003)="io.homeassistant.companion.android.minimal.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "io.homeassistant.companion.android.minimal.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
      A: android:name(0x01010003)="io.homeassistant.companion.android.minimal.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "io.homeassistant.companion.android.minimal.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
      A: android:name(0x01010003)="android.hardware.bluetooth_le" (Raw: "android.hardware.bluetooth_le")
      A: android:name(0x01010003)="android.hardware.camera.front" (Raw: "android.hardware.camera.front")
      A: android:name(0x01010003)="android.hardware.camera.autofocus" (Raw: "android.hardware.camera.autofocus")
      A: android:name(0x01010003)="android.hardware.camera.flash" (Raw: "android.hardware.camera.flash")
      A: android:name(0x01010003)="android.hardware.screen.landscape" (Raw: "android.hardware.screen.landscape")
      A: android:name(0x01010003)="android.hardware.wifi" (Raw: "android.hardware.wifi")
    E: application (line=150)
      A: android:label(0x01010001)=@0x7f12004a
      A: android:name(0x01010003)="io.homeassistant.companion.android.HomeAssistantApplication" (Raw: "io.homeassistant.companion.android.HomeAssistantApplication")
      A: android:dataExtractionRules(0x0101063e)=@0x7f150002
      E: activity (line=168)
        A: android:name(0x01010003)="io.homeassistant.companion.android.launch.LaunchActivity" (Raw: "io.homeassistant.companion.android.launch.LaunchActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
          A: android:name(0x01010003)="android.app.shortcuts" (Raw: "android.app.shortcuts")
        E: intent-filter (line=179)
          E: action (line=180)
            A: android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
          E: category (line=182)
            A: android:name(0x01010003)="android.intent.category.LAUNCHER" (Raw: "android.intent.category.LAUNCHER")
        E: intent-filter (line=184)
          E: action (line=185)
            A: android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
          E: category (line=187)
            A: android:name(0x01010003)="android.intent.category.LEANBACK_LAUNCHER" (Raw: "android.intent.category.LEANBACK_LAUNCHER")
      E: activity-alias (line=197)
        A: android:name(0x01010003)="io.homeassistant.companion.android.launch.LaunchOverLockScreen" (Raw: "io.homeassistant.companion.android.launch.LaunchOverLockScreen")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity-alias (line=206)
        A: android:name(0x01010003)="io.homeassistant.companion.android.launch.LauncherAlias" (Raw: "io.homeassistant.companion.android.launch.LauncherAlias")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=211)
          E: action (line=212)
            A: android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
          E: category (line=214)
            A: android:name(0x01010003)="android.intent.category.HOME" (Raw: "android.intent.category.HOME")
          E: category (line=215)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
      E: activity (line=219)
        A: android:name(0x01010003)="io.homeassistant.companion.android.developer.DevPlaygroundActivity" (Raw: "io.homeassistant.companion.android.developer.DevPlaygroundActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
      E: activity (line=222)
        A: android:name(0x01010003)="io.homeassistant.companion.android.developer.catalog.HAComposeCatalogActivity" (Raw: "io.homeassistant.companion.android.developer.catalog.HAComposeCatalogActivity")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=225)
        A: android:label(0x01010001)=@0x7f12019f
        A: android:name(0x01010003)="io.homeassistant.companion.android.developer.nfc.DebugNfcTagEmulatorActivity" (Raw: "io.homeassistant.companion.android.developer.nfc.DebugNfcTagEmulatorActivity")
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="io.homeassistant.companion.android.developer.nfc.DebugNfcTagEmulatorService" (Raw: "io.homeassistant.companion.android.developer.nfc.DebugNfcTagEmulatorService")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=237)
          E: action (line=238)
            A: android:name(0x01010003)="android.nfc.cardemulation.action.HOST_APDU_SERVICE" (Raw: "android.nfc.cardemulation.action.HOST_APDU_SERVICE")
          A: android:name(0x01010003)="android.nfc.cardemulation.host_apdu_service" (Raw: "android.nfc.cardemulation.host_apdu_service")
      E: activity (line=246)
        A: android:name(0x01010003)="io.homeassistant.companion.android.developer.DemoExoPlayerActivity" (Raw: "io.homeassistant.companion.android.developer.DemoExoPlayerActivity")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=254)
        A: android:name(0x01010003)="io.homeassistant.companion.android.HiltComponentActivity" (Raw: "io.homeassistant.companion.android.HiltComponentActivity")
      E: activity (line=259)
        A: android:label(0x01010001)=@0x7f1206a5
        A: android:name(0x01010003)="io.homeassistant.companion.android.nfc.TagReaderActivity" (Raw: "io.homeassistant.companion.android.nfc.TagReaderActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=267)
          E: action (line=268)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=270)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: category (line=271)
            A: android:name(0x01010003)="android.intent.category.BROWSABLE" (Raw: "android.intent.category.BROWSABLE")
        E: intent-filter (line=278)
          E: action (line=279)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=281)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: category (line=282)
            A: android:name(0x01010003)="android.intent.category.BROWSABLE" (Raw: "android.intent.category.BROWSABLE")
      E: activity-alias (line=291)
        A: android:name(0x01010003)="io.homeassistant.companion.android.nfc.TagScannedActivity" (Raw: "io.homeassistant.companion.android.nfc.TagScannedActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=296)
          E: action (line=297)
            A: android:name(0x01010003)="android.nfc.action.NDEF_DISCOVERED" (Raw: "android.nfc.action.NDEF_DISCOVERED")
          E: category (line=299)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
        A: android:name(0x01010003)="io.homeassistant.companion.android.websocket.WebsocketBroadcastReceiver" (Raw: "io.homeassistant.companion.android.websocket.WebsocketBroadcastReceiver")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=311)
          E: action (line=312)
            A: android:name(0x01010003)="android.intent.action.BOOT_COMPLETED" (Raw: "android.intent.action.BOOT_COMPLETED")
          E: action (line=313)
            A: android:name(0x01010003)="android.intent.action.MY_PACKAGE_REPLACED" (Raw: "android.intent.action.MY_PACKAGE_REPLACED")
          E: action (line=314)
            A: android:name(0x01010003)="android.intent.action.MY_PACKAGE_SUSPENDED" (Raw: "android.intent.action.MY_PACKAGE_SUSPENDED")
          E: action (line=315)
            A: android:name(0x01010003)="android.intent.action.MY_PACKAGE_UNSUSPENDED" (Raw: "android.intent.action.MY_PACKAGE_UNSUSPENDED")
          E: action (line=316)
            A: android:name(0x01010003)="android.intent.action.QUICKBOOT_POWERON" (Raw: "android.intent.action.QUICKBOOT_POWERON")
          E: action (line=317)
            A: android:name(0x01010003)="com.htc.intent.action.QUICKBOOT_POWERON" (Raw: "com.htc.intent.action.QUICKBOOT_POWERON")
          E: action (line=318)
            A: android:name(0x01010003)="android.app.action.NEXT_ALARM_CLOCK_CHANGED" (Raw: "android.app.action.NEXT_ALARM_CLOCK_CHANGED")
          E: action (line=319)
            A: android:name(0x01010003)="android.bluetooth.device.action.ACL_CONNECTED" (Raw: "android.bluetooth.device.action.ACL_CONNECTED")
          E: action (line=320)
            A: android:name(0x01010003)="android.bluetooth.device.action.ACL_DISCONNECTED" (Raw: "android.bluetooth.device.action.ACL_DISCONNECTED")
        A: android:name(0x01010003)="io.homeassistant.companion.android.sensors.SensorReceiver" (Raw: "io.homeassistant.companion.android.sensors.SensorReceiver")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=326)
          E: action (line=327)
            A: android:name(0x01010003)="android.intent.action.BOOT_COMPLETED" (Raw: "android.intent.action.BOOT_COMPLETED")
          E: action (line=328)
            A: android:name(0x01010003)="android.intent.action.MY_PACKAGE_REPLACED" (Raw: "android.intent.action.MY_PACKAGE_REPLACED")
          E: action (line=329)
            A: android:name(0x01010003)="android.intent.action.MY_PACKAGE_SUSPENDED" (Raw: "android.intent.action.MY_PACKAGE_SUSPENDED")
          E: action (line=330)
            A: android:name(0x01010003)="android.intent.action.MY_PACKAGE_UNSUSPENDED" (Raw: "android.intent.action.MY_PACKAGE_UNSUSPENDED")
          E: action (line=331)
            A: android:name(0x01010003)="android.intent.action.QUICKBOOT_POWERON" (Raw: "android.intent.action.QUICKBOOT_POWERON")
          E: action (line=332)
            A: android:name(0x01010003)="com.htc.intent.action.QUICKBOOT_POWERON" (Raw: "com.htc.intent.action.QUICKBOOT_POWERON")
          E: action (line=333)
            A: android:name(0x01010003)="android.app.action.NEXT_ALARM_CLOCK_CHANGED" (Raw: "android.app.action.NEXT_ALARM_CLOCK_CHANGED")
          E: action (line=334)
            A: android:name(0x01010003)="android.bluetooth.device.action.ACL_CONNECTED" (Raw: "android.bluetooth.device.action.ACL_CONNECTED")
          E: action (line=335)
            A: android:name(0x01010003)="android.bluetooth.device.action.ACL_DISCONNECTED" (Raw: "android.bluetooth.device.action.ACL_DISCONNECTED")
          E: action (line=336)
            A: android:name(0x01010003)="io.homeassistant.companion.android.UPDATE_SENSORS" (Raw: "io.homeassistant.companion.android.UPDATE_SENSORS")
        A: android:label(0x01010001)=@0x7f120776
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.button.ButtonWidget" (Raw: "io.homeassistant.companion.android.widgets.button.ButtonWidget")
        A: android:exported(0x01010010)=(type 0x12)0x0
        E: intent-filter (line=343)
          E: action (line=344)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_UPDATE" (Raw: "android.appwidget.action.APPWIDGET_UPDATE")
          E: action (line=345)
            A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.ButtonWidget.CALL_SERVICE" (Raw: "io.homeassistant.companion.android.widgets.ButtonWidget.CALL_SERVICE")
          E: action (line=346)
            A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.ButtonWidget.CALL_SERVICE_AUTH" (Raw: "io.homeassistant.companion.android.widgets.ButtonWidget.CALL_SERVICE_AUTH")
          A: android:name(0x01010003)="android.appwidget.provider" (Raw: "android.appwidget.provider")
        A: android:label(0x01010001)=@0x7f120778
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.camera.CameraWidget" (Raw: "io.homeassistant.companion.android.widgets.camera.CameraWidget")
        A: android:exported(0x01010010)=(type 0x12)0x0
        E: intent-filter (line=357)
          E: action (line=358)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_UPDATE" (Raw: "android.appwidget.action.APPWIDGET_UPDATE")
          E: action (line=359)
            A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.camera.CameraWidget.UPDATE_IMAGE" (Raw: "io.homeassistant.companion.android.widgets.camera.CameraWidget.UPDATE_IMAGE")
          A: android:name(0x01010003)="android.appwidget.provider" (Raw: "android.appwidget.provider")
        A: android:label(0x01010001)=@0x7f120795
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.entity.EntityWidget" (Raw: "io.homeassistant.companion.android.widgets.entity.EntityWidget")
        A: android:exported(0x01010010)=(type 0x12)0x0
        E: intent-filter (line=370)
          E: action (line=371)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_UPDATE" (Raw: "android.appwidget.action.APPWIDGET_UPDATE")
          A: android:name(0x01010003)="android.appwidget.provider" (Raw: "android.appwidget.provider")
        A: android:label(0x01010001)=@0x7f1207a5
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.todo.TodoWidget" (Raw: "io.homeassistant.companion.android.widgets.todo.TodoWidget")
        A: android:exported(0x01010010)=(type 0x12)0x0
        E: intent-filter (line=382)
          E: action (line=383)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_UPDATE" (Raw: "android.appwidget.action.APPWIDGET_UPDATE")
          A: android:name(0x01010003)="android.appwidget.provider" (Raw: "android.appwidget.provider")
        A: android:label(0x01010001)=@0x7f120786
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.mediaplayer.MediaPlayerControlsWidget" (Raw: "io.homeassistant.companion.android.widgets.mediaplayer.MediaPlayerControlsWidget")
        A: android:exported(0x01010010)=(type 0x12)0x0
        E: intent-filter (line=394)
          E: action (line=395)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_UPDATE" (Raw: "android.appwidget.action.APPWIDGET_UPDATE")
          E: action (line=396)
            A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.UPDATE_MEDIA_IMAGE" (Raw: "io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.UPDATE_MEDIA_IMAGE")
          E: action (line=397)
            A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.CALL_PREV_TRACK" (Raw: "io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.CALL_PREV_TRACK")
          E: action (line=398)
            A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.CALL_REWIND" (Raw: "io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.CALL_REWIND")
          E: action (line=399)
            A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.CALL_PLAYPAUSE" (Raw: "io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.CALL_PLAYPAUSE")
          E: action (line=400)
            A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.CALL_FASTFORWARD" (Raw: "io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.CALL_FASTFORWARD")
          E: action (line=401)
            A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.CALL_NEXT_TRACK" (Raw: "io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget.CALL_NEXT_TRACK")
          A: android:name(0x01010003)="android.appwidget.provider" (Raw: "android.appwidget.provider")
        A: android:label(0x01010001)=@0x7f1206b7
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.template.TemplateWidget" (Raw: "io.homeassistant.companion.android.widgets.template.TemplateWidget")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=412)
          E: action (line=413)
            A: android:name(0x01010003)="android.intent.action.BOOT_COMPLETED" (Raw: "android.intent.action.BOOT_COMPLETED")
          E: action (line=414)
            A: android:name(0x01010003)="android.intent.action.QUICKBOOT_POWERON" (Raw: "android.intent.action.QUICKBOOT_POWERON")
          E: action (line=415)
            A: android:name(0x01010003)="com.htc.intent.action.QUICKBOOT_POWERON" (Raw: "com.htc.intent.action.QUICKBOOT_POWERON")
          E: action (line=416)
            A: android:name(0x01010003)="android.app.action.NEXT_ALARM_CLOCK_CHANGED" (Raw: "android.app.action.NEXT_ALARM_CLOCK_CHANGED")
          E: action (line=417)
            A: android:name(0x01010003)="android.bluetooth.device.action.ACL_CONNECTED" (Raw: "android.bluetooth.device.action.ACL_CONNECTED")
          E: action (line=418)
            A: android:name(0x01010003)="android.bluetooth.device.action.ACL_DISCONNECTED" (Raw: "android.bluetooth.device.action.ACL_DISCONNECTED")
          E: action (line=419)
            A: android:name(0x01010003)="io.homeassistant.companion.android.background.REQUEST_SENSORS_UPDATE" (Raw: "io.homeassistant.companion.android.background.REQUEST_SENSORS_UPDATE")
          E: action (line=420)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_UPDATE" (Raw: "android.appwidget.action.APPWIDGET_UPDATE")
          A: android:name(0x01010003)="android.appwidget.provider" (Raw: "android.appwidget.provider")
      E: activity (line=428)
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.button.ButtonWidgetConfigureActivity" (Raw: "io.homeassistant.companion.android.widgets.button.ButtonWidgetConfigureActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=432)
          E: action (line=433)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_CONFIGURE" (Raw: "android.appwidget.action.APPWIDGET_CONFIGURE")
      E: activity (line=436)
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.common.WidgetAuthenticationActivity" (Raw: "io.homeassistant.companion.android.widgets.common.WidgetAuthenticationActivity")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=442)
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.camera.CameraWidgetConfigureActivity" (Raw: "io.homeassistant.companion.android.widgets.camera.CameraWidgetConfigureActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=446)
          E: action (line=447)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_CONFIGURE" (Raw: "android.appwidget.action.APPWIDGET_CONFIGURE")
      E: activity (line=450)
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.entity.EntityWidgetConfigureActivity" (Raw: "io.homeassistant.companion.android.widgets.entity.EntityWidgetConfigureActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=454)
          E: action (line=455)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_CONFIGURE" (Raw: "android.appwidget.action.APPWIDGET_CONFIGURE")
      E: activity (line=458)
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.todo.TodoWidgetConfigureActivity" (Raw: "io.homeassistant.companion.android.widgets.todo.TodoWidgetConfigureActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=462)
          E: action (line=463)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_CONFIGURE" (Raw: "android.appwidget.action.APPWIDGET_CONFIGURE")
      E: activity (line=466)
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.mediaplayer.MediaPlayerControlsWidgetConfigureActivity" (Raw: "io.homeassistant.companion.android.widgets.mediaplayer.MediaPlayerControlsWidgetConfigureActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=470)
          E: action (line=471)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_CONFIGURE" (Raw: "android.appwidget.action.APPWIDGET_CONFIGURE")
      E: activity (line=474)
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.template.TemplateWidgetConfigureActivity" (Raw: "io.homeassistant.companion.android.widgets.template.TemplateWidgetConfigureActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=477)
          E: action (line=478)
            A: android:name(0x01010003)="android.appwidget.action.APPWIDGET_CONFIGURE" (Raw: "android.appwidget.action.APPWIDGET_CONFIGURE")
        A: android:name(0x01010003)="io.homeassistant.companion.android.sensors.NotificationSensorManager" (Raw: "io.homeassistant.companion.android.sensors.NotificationSensorManager")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=490)
          E: action (line=491)
            A: android:name(0x01010003)="android.service.notification.NotificationListenerService" (Raw: "android.service.notification.NotificationListenerService")
        A: android:name(0x01010003)="io.homeassistant.companion.android.controls.HaControlsProviderService" (Raw: "io.homeassistant.companion.android.controls.HaControlsProviderService")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
          A: android:name(0x01010003)="android.service.controls.META_DATA_PANEL_ACTIVITY" (Raw: "android.service.controls.META_DATA_PANEL_ACTIVITY")
        E: intent-filter (line=502)
          E: action (line=503)
            A: android:name(0x01010003)="android.service.controls.ControlsProviderService" (Raw: "android.service.controls.ControlsProviderService")
      E: activity (line=507)
        A: android:name(0x01010003)="io.homeassistant.companion.android.controls.HaControlsPanelActivity" (Raw: "io.homeassistant.companion.android.controls.HaControlsPanelActivity")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        A: android:name(0x01010003)="io.homeassistant.companion.android.sensors.LocationSensorReceiver" (Raw: "io.homeassistant.companion.android.sensors.LocationSensorReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="io.homeassistant.companion.android.sensors.RequestAccurateLocationReceiver" (Raw: "io.homeassistant.companion.android.sensors.RequestAccurateLocationReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=530)
          E: action (line=531)
            A: android:name(0x01010003)="io.homeassistant.companion.android.background.REQUEST_ACCURATE_UPDATE" (Raw: "io.homeassistant.companion.android.background.REQUEST_ACCURATE_UPDATE")
        A: android:name(0x01010003)="io.homeassistant.companion.android.sensors.ActivitySensorReceiver" (Raw: "io.homeassistant.companion.android.sensors.ActivitySensorReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
        E: intent-filter (line=538)
          E: action (line=539)
            A: android:name(0x01010003)="io.homeassistant.companion.android.background.REQUEST_ACTIVITY_UPDATES" (Raw: "io.homeassistant.companion.android.background.REQUEST_ACTIVITY_UPDATES")
      E: activity (line=543)
        A: android:name(0x01010003)="io.homeassistant.companion.android.launch.link.LinkActivity" (Raw: "io.homeassistant.companion.android.launch.link.LinkActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=547)
          E: action (line=548)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=550)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: category (line=551)
            A: android:name(0x01010003)="android.intent.category.BROWSABLE" (Raw: "android.intent.category.BROWSABLE")
        E: intent-filter (line=558)
          E: action (line=559)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=561)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: category (line=562)
            A: android:name(0x01010003)="android.intent.category.BROWSABLE" (Raw: "android.intent.category.BROWSABLE")
        E: intent-filter (line=569)
          E: action (line=570)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=572)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: category (line=573)
            A: android:name(0x01010003)="android.intent.category.BROWSABLE" (Raw: "android.intent.category.BROWSABLE")
        E: intent-filter (line=579)
          E: action (line=580)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=582)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: category (line=583)
            A: android:name(0x01010003)="android.intent.category.BROWSABLE" (Raw: "android.intent.category.BROWSABLE")
      E: activity (line=590)
        A: android:name(0x01010003)="io.homeassistant.companion.android.settings.wear.SettingsWearActivity" (Raw: "io.homeassistant.companion.android.settings.wear.SettingsWearActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=596)
          E: action (line=597)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=599)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: category (line=600)
            A: android:name(0x01010003)="android.intent.category.BROWSABLE" (Raw: "android.intent.category.BROWSABLE")
      E: activity (line=607)
        A: android:name(0x01010003)="io.homeassistant.companion.android.settings.wear.views.SettingsWearMainView" (Raw: "io.homeassistant.companion.android.settings.wear.views.SettingsWearMainView")
        A: android:name(0x01010003)="io.homeassistant.companion.android.onboarding.WearOnboardingListener" (Raw: "io.homeassistant.companion.android.onboarding.WearOnboardingListener")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=616)
          E: action (line=617)
            A: android:name(0x01010003)="com.google.android.gms.wearable.MESSAGE_RECEIVED" (Raw: "com.google.android.gms.wearable.MESSAGE_RECEIVED")
          E: action (line=618)
            A: android:name(0x01010003)="com.google.android.gms.wearable.DATA_CHANGED" (Raw: "com.google.android.gms.wearable.DATA_CHANGED")
      E: activity (line=627)
        A: android:name(0x01010003)="io.homeassistant.companion.android.settings.sensor.HealthConnectPermissionActivity" (Raw: "io.homeassistant.companion.android.settings.sensor.HealthConnectPermissionActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=630)
          E: action (line=631)
            A: android:name(0x01010003)="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" (Raw: "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE")
      E: activity-alias (line=635)
        A: android:name(0x01010003)="io.homeassistant.companion.android.ViewHealthConnectPermissionUsageActivity" (Raw: "io.homeassistant.companion.android.ViewHealthConnectPermissionUsageActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=640)
          E: action (line=641)
            A: android:name(0x01010003)="android.intent.action.VIEW_PERMISSION_USAGE" (Raw: "android.intent.action.VIEW_PERMISSION_USAGE")
          E: category (line=643)
            A: android:name(0x01010003)="android.intent.category.HEALTH_PERMISSIONS" (Raw: "android.intent.category.HEALTH_PERMISSIONS")
      E: activity (line=646)
        A: android:name(0x01010003)="io.homeassistant.companion.android.webview.WebViewActivity" (Raw: "io.homeassistant.companion.android.webview.WebViewActivity")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=649)
        A: android:name(0x01010003)="io.homeassistant.companion.android.settings.SettingsActivity" (Raw: "io.homeassistant.companion.android.settings.SettingsActivity")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=655)
        A: android:label(0x01010001)=@0x7f12043a
        A: android:name(0x01010003)="io.homeassistant.companion.android.nfc.NfcSetupActivity" (Raw: "io.homeassistant.companion.android.nfc.NfcSetupActivity")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=660)
        A: android:name(0x01010003)="io.homeassistant.companion.android.share.ShareActivity" (Raw: "io.homeassistant.companion.android.share.ShareActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=663)
          E: action (line=664)
            A: android:name(0x01010003)="android.intent.action.SEND" (Raw: "android.intent.action.SEND")
          E: category (line=666)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
      E: activity (line=671)
        A: android:name(0x01010003)="io.homeassistant.companion.android.assist.AssistActivity" (Raw: "io.homeassistant.companion.android.assist.AssistActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=680)
          E: action (line=681)
            A: android:name(0x01010003)="android.intent.action.ASSIST" (Raw: "android.intent.action.ASSIST")
          E: category (line=683)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
      E: activity-alias (line=687)
        A: android:name(0x01010003)="io.homeassistant.companion.android.assist.VoiceCommandIntentActivity" (Raw: "io.homeassistant.companion.android.assist.VoiceCommandIntentActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=691)
          E: action (line=692)
            A: android:name(0x01010003)="android.intent.action.VOICE_COMMAND" (Raw: "android.intent.action.VOICE_COMMAND")
          E: category (line=694)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
      E: activity (line=698)
        A: android:label(0x01010001)=@0x7f120068
        A: android:name(0x01010003)="io.homeassistant.companion.android.widgets.assist.AssistShortcutActivity" (Raw: "io.homeassistant.companion.android.widgets.assist.AssistShortcutActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=705)
          E: action (line=706)
            A: android:name(0x01010003)="android.intent.action.CREATE_SHORTCUT" (Raw: "android.intent.action.CREATE_SHORTCUT")
        A: android:name(0x01010003)="io.homeassistant.companion.android.assist.service.AssistVoiceInteractionService" (Raw: "io.homeassistant.companion.android.assist.service.AssistVoiceInteractionService")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
          A: android:name(0x01010003)="android.voice_interaction" (Raw: "android.voice_interaction")
        E: intent-filter (line=718)
          E: action (line=719)
            A: android:name(0x01010003)="android.service.voice.VoiceInteractionService" (Raw: "android.service.voice.VoiceInteractionService")
        A: android:name(0x01010003)="io.homeassistant.companion.android.assist.service.AssistVoiceInteractionSessionService" (Raw: "io.homeassistant.companion.android.assist.service.AssistVoiceInteractionSessionService")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        A: android:name(0x01010003)="io.homeassistant.companion.android.assist.service.AssistRecognitionService" (Raw: "io.homeassistant.companion.android.assist.service.AssistRecognitionService")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=730)
          E: action (line=731)
            A: android:name(0x01010003)="android.speech.RecognitionService" (Raw: "android.speech.RecognitionService")
          E: category (line=733)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          A: android:name(0x01010003)="android.speech" (Raw: "android.speech")
        A: android:name(0x01010003)="androidx.core.content.FileProvider" (Raw: "androidx.core.content.FileProvider")
        A: android:exported(0x01010010)=(type 0x12)0x0
          A: android:name(0x01010003)="android.support.FILE_PROVIDER_PATHS" (Raw: "android.support.FILE_PROVIDER_PATHS")
      E: activity (line=751)
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.TilePreferenceActivity" (Raw: "io.homeassistant.companion.android.qs.TilePreferenceActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=755)
          E: action (line=756)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE_PREFERENCES" (Raw: "android.service.quicksettings.action.QS_TILE_PREFERENCES")
        A: android:label(0x01010001)=@0x7f1206db
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile1Service" (Raw: "io.homeassistant.companion.android.qs.Tile1Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=766)
          E: action (line=767)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206e6
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile2Service" (Raw: "io.homeassistant.companion.android.qs.Tile2Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=776)
          E: action (line=777)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206f1
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile3Service" (Raw: "io.homeassistant.companion.android.qs.Tile3Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=786)
          E: action (line=787)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206fc
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile4Service" (Raw: "io.homeassistant.companion.android.qs.Tile4Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=796)
          E: action (line=797)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206fe
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile5Service" (Raw: "io.homeassistant.companion.android.qs.Tile5Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=806)
          E: action (line=807)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206ff
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile6Service" (Raw: "io.homeassistant.companion.android.qs.Tile6Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=816)
          E: action (line=817)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f120700
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile7Service" (Raw: "io.homeassistant.companion.android.qs.Tile7Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=826)
          E: action (line=827)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f120701
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile8Service" (Raw: "io.homeassistant.companion.android.qs.Tile8Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=836)
          E: action (line=837)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f120702
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile9Service" (Raw: "io.homeassistant.companion.android.qs.Tile9Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=846)
          E: action (line=847)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206dc
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile10Service" (Raw: "io.homeassistant.companion.android.qs.Tile10Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=856)
          E: action (line=857)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206dd
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile11Service" (Raw: "io.homeassistant.companion.android.qs.Tile11Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=866)
          E: action (line=867)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206de
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile12Service" (Raw: "io.homeassistant.companion.android.qs.Tile12Service")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=876)
          E: action (line=877)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206df
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile13Service" (Raw: "io.homeassistant.companion.android.qs.Tile13Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=887)
          E: action (line=888)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206e0
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile14Service" (Raw: "io.homeassistant.companion.android.qs.Tile14Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=898)
          E: action (line=899)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206e1
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile15Service" (Raw: "io.homeassistant.companion.android.qs.Tile15Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=909)
          E: action (line=910)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206e2
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile16Service" (Raw: "io.homeassistant.companion.android.qs.Tile16Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=920)
          E: action (line=921)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206e3
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile17Service" (Raw: "io.homeassistant.companion.android.qs.Tile17Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=931)
          E: action (line=932)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206e4
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile18Service" (Raw: "io.homeassistant.companion.android.qs.Tile18Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=942)
          E: action (line=943)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206e5
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile19Service" (Raw: "io.homeassistant.companion.android.qs.Tile19Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=953)
          E: action (line=954)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206e7
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile20Service" (Raw: "io.homeassistant.companion.android.qs.Tile20Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=964)
          E: action (line=965)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206e8
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile21Service" (Raw: "io.homeassistant.companion.android.qs.Tile21Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=975)
          E: action (line=976)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206e9
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile22Service" (Raw: "io.homeassistant.companion.android.qs.Tile22Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=986)
          E: action (line=987)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206ea
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile23Service" (Raw: "io.homeassistant.companion.android.qs.Tile23Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=997)
          E: action (line=998)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206eb
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile24Service" (Raw: "io.homeassistant.companion.android.qs.Tile24Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1008)
          E: action (line=1009)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206ec
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile25Service" (Raw: "io.homeassistant.companion.android.qs.Tile25Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1019)
          E: action (line=1020)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206ed
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile26Service" (Raw: "io.homeassistant.companion.android.qs.Tile26Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1030)
          E: action (line=1031)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206ee
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile27Service" (Raw: "io.homeassistant.companion.android.qs.Tile27Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1041)
          E: action (line=1042)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206ef
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile28Service" (Raw: "io.homeassistant.companion.android.qs.Tile28Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1052)
          E: action (line=1053)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206f0
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile29Service" (Raw: "io.homeassistant.companion.android.qs.Tile29Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1063)
          E: action (line=1064)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206f2
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile30Service" (Raw: "io.homeassistant.companion.android.qs.Tile30Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1074)
          E: action (line=1075)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206f3
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile31Service" (Raw: "io.homeassistant.companion.android.qs.Tile31Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1085)
          E: action (line=1086)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206f4
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile32Service" (Raw: "io.homeassistant.companion.android.qs.Tile32Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1096)
          E: action (line=1097)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206f5
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile33Service" (Raw: "io.homeassistant.companion.android.qs.Tile33Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1107)
          E: action (line=1108)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206f6
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile34Service" (Raw: "io.homeassistant.companion.android.qs.Tile34Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1118)
          E: action (line=1119)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206f7
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile35Service" (Raw: "io.homeassistant.companion.android.qs.Tile35Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1129)
          E: action (line=1130)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206f8
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile36Service" (Raw: "io.homeassistant.companion.android.qs.Tile36Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1140)
          E: action (line=1141)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206f9
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile37Service" (Raw: "io.homeassistant.companion.android.qs.Tile37Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1151)
          E: action (line=1152)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206fa
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile38Service" (Raw: "io.homeassistant.companion.android.qs.Tile38Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1162)
          E: action (line=1163)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206fb
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile39Service" (Raw: "io.homeassistant.companion.android.qs.Tile39Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1173)
          E: action (line=1174)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:label(0x01010001)=@0x7f1206fd
        A: android:name(0x01010003)="io.homeassistant.companion.android.qs.Tile40Service" (Raw: "io.homeassistant.companion.android.qs.Tile40Service")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1184)
          E: action (line=1185)
            A: android:name(0x01010003)="android.service.quicksettings.action.QS_TILE" (Raw: "android.service.quicksettings.action.QS_TILE")
        A: android:name(0x01010003)="androidx.appcompat.app.AppLocalesMetadataHolderService" (Raw: "androidx.appcompat.app.AppLocalesMetadataHolderService")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0x0
          A: android:name(0x01010003)="autoStoreLocales" (Raw: "autoStoreLocales")
        A: android:name(0x01010003)="io.homeassistant.companion.android.notifications.NotificationActionReceiver" (Raw: "io.homeassistant.companion.android.notifications.NotificationActionReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="androidx.work.impl.foreground.SystemForegroundService" (Raw: "androidx.work.impl.foreground.SystemForegroundService")
        A: android:enabled(0x0101000e)=@0x7f050004
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="io.homeassistant.companion.android.common.sensors.StopBeaconScanningReceiver" (Raw: "io.homeassistant.companion.android.common.sensors.StopBeaconScanningReceiver")
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="io.homeassistant.companion.android.common.notifications.NotificationDeleteReceiver" (Raw: "io.homeassistant.companion.android.common.notifications.NotificationDeleteReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=1215)
        A: android:name(0x01010003)="androidx.media3.session.BluetoothValidationActivity" (Raw: "androidx.media3.session.BluetoothValidationActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1222)
          E: action (line=1225)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
        A: android:name(0x01010003)="androidx.health.platform.client.impl.sdkservice.HealthDataSdkService" (Raw: "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1239)
          E: action (line=1240)
            A: android:name(0x01010003)="androidx.health.platform.client.ACTION_BIND_SDK_SERVICE" (Raw: "androidx.health.platform.client.ACTION_BIND_SDK_SERVICE")
        A: android:name(0x01010003)="org.altbeacon.beacon.startup.StartupBroadcastReceiver" (Raw: "org.altbeacon.beacon.startup.StartupBroadcastReceiver")
        A: android:exported(0x01010010)=(type 0x12)0x0
        E: intent-filter (line=1247)
          E: action (line=1248)
            A: android:name(0x01010003)="android.intent.action.BOOT_COMPLETED" (Raw: "android.intent.action.BOOT_COMPLETED")
          E: action (line=1249)
            A: android:name(0x01010003)="android.intent.action.ACTION_POWER_CONNECTED" (Raw: "android.intent.action.ACTION_POWER_CONNECTED")
          E: action (line=1250)
            A: android:name(0x01010003)="android.intent.action.ACTION_POWER_DISCONNECTED" (Raw: "android.intent.action.ACTION_POWER_DISCONNECTED")
        A: android:label(0x01010001)="beacon" (Raw: "beacon")
        A: android:name(0x01010003)="org.altbeacon.beacon.service.BeaconService" (Raw: "org.altbeacon.beacon.service.BeaconService")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="org.altbeacon.beacon.BeaconIntentProcessor" (Raw: "org.altbeacon.beacon.BeaconIntentProcessor")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="org.altbeacon.beacon.service.ScanJob" (Raw: "org.altbeacon.beacon.service.ScanJob")
        A: android:exported(0x01010010)=(type 0x12)0x0
          A: android:name(0x01010003)="immediateScanJobId" (Raw: "immediateScanJobId")
          A: android:name(0x01010003)="periodicScanJobId" (Raw: "periodicScanJobId")
        A: android:name(0x01010003)="org.altbeacon.bluetooth.BluetoothTestJob" (Raw: "org.altbeacon.bluetooth.BluetoothTestJob")
        A: android:exported(0x01010010)=(type 0x12)0x0
          A: android:name(0x01010003)="jobId" (Raw: "jobId")
      E: activity (line=1285)
        A: android:name(0x01010003)="androidx.car.app.CarAppPermissionActivity" (Raw: "androidx.car.app.CarAppPermissionActivity")
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="androidx.car.app.notification.CarAppNotificationBroadcastReceiver" (Raw: "androidx.car.app.notification.CarAppNotificationBroadcastReceiver")
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="androidx.room3.MultiInstanceInvalidationService" (Raw: "androidx.room3.MultiInstanceInvalidationService")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=1304)
        A: android:name(0x01010003)="androidx.glance.appwidget.action.ActionTrampolineActivity" (Raw: "androidx.glance.appwidget.action.ActionTrampolineActivity")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=1310)
        A: android:name(0x01010003)="androidx.glance.appwidget.action.InvisibleActionTrampolineActivity" (Raw: "androidx.glance.appwidget.action.InvisibleActionTrampolineActivity")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver" (Raw: "androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="androidx.glance.appwidget.UnmanagedSessionReceiver" (Raw: "androidx.glance.appwidget.UnmanagedSessionReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="androidx.glance.appwidget.MyPackageReplacedReceiver" (Raw: "androidx.glance.appwidget.MyPackageReplacedReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
        E: intent-filter (line=1331)
          E: action (line=1332)
            A: android:name(0x01010003)="android.intent.action.MY_PACKAGE_REPLACED" (Raw: "android.intent.action.MY_PACKAGE_REPLACED")
        A: android:name(0x01010003)="androidx.glance.appwidget.GlanceRemoteViewsService" (Raw: "androidx.glance.appwidget.GlanceRemoteViewsService")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        A: android:name(0x01010003)="androidx.startup.InitializationProvider" (Raw: "androidx.startup.InitializationProvider")
        A: android:exported(0x01010010)=(type 0x12)0x0
          A: android:name(0x01010003)="androidx.emoji2.text.EmojiCompatInitializer" (Raw: "androidx.emoji2.text.EmojiCompatInitializer")
          A: android:name(0x01010003)="androidx.work.WorkManagerInitializer" (Raw: "androidx.work.WorkManagerInitializer")
          A: android:name(0x01010003)="androidx.lifecycle.ProcessLifecycleInitializer" (Raw: "androidx.lifecycle.ProcessLifecycleInitializer")
          A: android:name(0x01010003)="androidx.profileinstaller.ProfileInstallerInitializer" (Raw: "androidx.profileinstaller.ProfileInstallerInitializer")
          A: android:name(0x01010003)="okhttp3.internal.platform.PlatformInitializer" (Raw: "okhttp3.internal.platform.PlatformInitializer")
        A: android:name(0x01010003)="androidx.work.impl.background.systemjob.SystemJobService" (Raw: "androidx.work.impl.background.systemjob.SystemJobService")
        A: android:enabled(0x0101000e)=@0x7f050005
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        A: android:name(0x01010003)="androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver" (Raw: "androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:name(0x01010003)="androidx.work.impl.background.systemalarm.RescheduleReceiver" (Raw: "androidx.work.impl.background.systemalarm.RescheduleReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0x0
        E: intent-filter (line=1379)
          E: action (line=1380)
            A: android:name(0x01010003)="android.intent.action.BOOT_COMPLETED" (Raw: "android.intent.action.BOOT_COMPLETED")
        A: android:name(0x01010003)="androidx.work.impl.diagnostics.DiagnosticsReceiver" (Raw: "androidx.work.impl.diagnostics.DiagnosticsReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1389)
          E: action (line=1390)
            A: android:name(0x01010003)="androidx.work.diagnostics.REQUEST_DIAGNOSTICS" (Raw: "androidx.work.diagnostics.REQUEST_DIAGNOSTICS")
        A: android:name(0x01010003)="androidx.core.widget.RemoteViewsCompatService" (Raw: "androidx.core.widget.RemoteViewsCompatService")
        A: android:name(0x01010003)="androidx.window.extensions" (Raw: "androidx.window.extensions")
        A: android:name(0x01010003)="androidx.window.sidecar" (Raw: "androidx.window.sidecar")
      E: activity (line=1405)
        A: android:name(0x01010003)="androidx.compose.ui.tooling.PreviewActivity" (Raw: "androidx.compose.ui.tooling.PreviewActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        A: android:name(0x01010003)="androidx.profileinstaller.ProfileInstallReceiver" (Raw: "androidx.profileinstaller.ProfileInstallReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=1415)
          E: action (line=1416)
            A: android:name(0x01010003)="androidx.profileinstaller.action.INSTALL_PROFILE" (Raw: "androidx.profileinstaller.action.INSTALL_PROFILE")
        E: intent-filter (line=1418)
          E: action (line=1419)
            A: android:name(0x01010003)="androidx.profileinstaller.action.SKIP_FILE" (Raw: "androidx.profileinstaller.action.SKIP_FILE")
        E: intent-filter (line=1421)
          E: action (line=1422)
            A: android:name(0x01010003)="androidx.profileinstaller.action.SAVE_PROFILE" (Raw: "androidx.profileinstaller.action.SAVE_PROFILE")
        E: intent-filter (line=1424)
          E: action (line=1425)
            A: android:name(0x01010003)="androidx.profileinstaller.action.BENCHMARK_OPERATION" (Raw: "androidx.profileinstaller.action.BENCHMARK_OPERATION")
        A: android:name(0x01010003)="androidx.room.MultiInstanceInvalidationService" (Raw: "androidx.room.MultiInstanceInvalidationService")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=1434)
        A: android:name(0x01010003)="com.journeyapps.barcodescanner.CaptureActivity" (Raw: "com.journeyapps.barcodescanner.CaptureActivity")
```

## 8. 是否仍存在 `Leaks` 启动入口

判定依据：合并后清单（第 7 节的转储）中不得出现 `Leaks` 标签或 `leakcanary` 组件名。

```text
（合并后清单中无匹配，符合预期）
```

### APK 归档条目中的匹配

```text
（无匹配。注意：DEX 中的类名引用需要按第 9 节的方法单独检查。）
```

## 9. 动态地址解析模块是否真的打进了 APK

DEX 中的类型描述符形如 `Lio/homeassistant/companion/android/common/data/endpoint/<Name>;`。

```text
classes.dex: 0 处 endpoint 包引用
classes10.dex: 0 处 endpoint 包引用
classes11.dex: 0 处 endpoint 包引用
classes12.dex: 0 处 endpoint 包引用
classes13.dex: 0 处 endpoint 包引用
classes14.dex: 40 处 endpoint 包引用
classes16.dex: 0 处 endpoint 包引用
classes17.dex: 0 处 endpoint 包引用
classes18.dex: 0 处 endpoint 包引用
classes19.dex: 0 处 endpoint 包引用
classes20.dex: 0 处 endpoint 包引用
classes21.dex: 0 处 endpoint 包引用
classes22.dex: 0 处 endpoint 包引用
classes23.dex: 0 处 endpoint 包引用
classes24.dex: 0 处 endpoint 包引用
classes25.dex: 0 处 endpoint 包引用
classes4.dex: 0 处 endpoint 包引用
classes5.dex: 0 处 endpoint 包引用
classes6.dex: 0 处 endpoint 包引用
classes9.dex: 0 处 endpoint 包引用
classes15.dex: 17 处 endpoint 包引用
classes2.dex: 327 处 endpoint 包引用
classes3.dex: 0 处 endpoint 包引用
classes8.dex: 14 处 endpoint 包引用
classes7.dex: 0 处 endpoint 包引用
合计：398
```

## 10. 入口数量核对

预期源码清单中恰好 3 个 `android.intent.action.MAIN` 入口：LAUNCHER、LEANBACK_LAUNCHER，以及默认禁用的 HOME 别名。

```text
源码清单中的 MAIN 条目数：3
```

## 11. 单元测试结果

```text
common/build/test-results/testDebugUnitTest/TEST-i-7EH65R5324NEQ.homeassistant.companion.android.common.data.integration.impl.entities.RegisterDeviceRequestTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-i-AN7MQ1AOJUNNG.companion.android.common.data.websocket.impl.entities.CompressedEntityTest$StateDeserialization.xml
tests="6" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-i-GTEKN27KA1SRO.homeassistant.companion.android.common.data.prefs.PrefsRepositoryImplTest$SaveScreenOrientation.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-i-RB6MA9F26R7NK.homeassistant.companion.android.common.data.integration.impl.entities.UpdateLocationRequestTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io-V74OKDP5O0VVG.companion.android.database.server.ServerConnectionInfoTest$InternalSsidColumnTypeConverterTest.xml
tests="7" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.h-V5NRMPBROSH86.companion.android.common.data.servers.ServerConnectionStateProviderImplTest$GetSecurityState.xml
tests="11" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.ho-1QUNSSCI511E6.companion.android.common.data.websocket.impl.WebSocketRepositoryImplTest$RegistryForDisplay.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.hom-NTNPJ66SU5CF2.android.common.data.integration.display.EntitiesForDisplayManagerTest$InContext$Resolution.xml
tests="27" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.hom-ORR3L3J64NSDI.android.common.data.integration.impl.IntegrationRepositoryImplTest$UpdateRegistrationTests.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.hom-S7FGPFV2ICSUM.android.common.data.servers.ServerConnectionStateProviderImplTest$CanSafelySendCredentials.xml
tests="7" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.hom-U76UQH8ONUBTK.companion.android.common.data.integration.impl.IntegrationRepositoryImplTest$EntityUpdates.xml
tests="7" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.hom-UGV4PH9Q3GR8E.companion.android.common.data.servers.ServerConnectionStateProviderImplTest$GetExternalUrl.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.home-5K5B2N8JMTJPU.android.common.data.connectivity.DefaultConnectivityCheckerTest$HomeAssistantVerification.xml
tests="16" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.home-UQ9B9RGPFBMNG.companion.android.common.data.integration.display.EntitiesForDisplayManagerTest$InContext.xml
tests="15" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homea-0G58HBNPBSRPA.android.common.data.websocket.impl.WebSocketRepositoryImplTest$RunAssistPipelineForVoice.xml
tests="11" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homea-EQT7FJD1DELH8.companion.android.common.data.servers.ServerManagerImplTest$AuthenticationRepositoryTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeas-5QQ7PBPKE9IPM.companion.android.common.data.servers.ServerManagerImplTest$ConnectionStateProviderTest.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeas-A5T8ULC6DLIQC.android.common.data.integration.display.EntitiesForDisplayManagerTest$InContext$Observe.xml
tests="8" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeas-MJ7GUP824NVLS.companion.android.common.data.websocket.impl.entities.EntityRegistryDisplayResponseTest.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeass-C24V3LHPOA980.companion.android.common.data.servers.ServerConnectionStateProviderImplTest$GetApiUrls.xml
tests="12" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeass-D4C3O9F2CDULM.companion.android.common.data.servers.ServerConnectionStateProviderImplTest$IsInternal.xml
tests="7" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassi-E862FCJA7SK0S.companion.android.common.data.websocket.impl.WebSocketCoreImplTest$DynamicAddressGate.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassi-T2FSKUORPKFHI.companion.android.common.data.servers.ServerManagerImplTest$IntegrationRepositoryTest.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassi-T9519PEJNDD3K.companion.android.common.data.integration.impl.entities.SensorRegistrationRequestTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassis-DSAJJV1ELK11M.companion.android.common.data.servers.ServerManagerImplTest$GetServerByWebhookIdTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassist-NE5JKT0MBAIRE.companion.android.common.data.servers.ServerManagerImplTest$WebSocketRepositoryTest.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassist-P69LA85SFQDHE.companion.android.common.data.servers.ServerConnectionStateProviderImplTest$UrlFlow.xml
tests="8" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassista-H8U6C136L095E.companion.android.common.data.integration.EntityTest$CompressedEntityStateToEntity.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.LocalStorageImplTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.assist.AssistAudioFocusImplTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.assist.AssistViewModelBaseTest.xml
tests="14" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.assist.DefaultAssistAudioStrategyTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.compose.composable.HAModalBottomSheetTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.compose.composable.HASearchFieldTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.CompositeX509ExtendedTrustManagerTest.xml
tests="12" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.TLSHelperTest.xml
tests="7" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.authentication.ServerRegistrationRepositoryTest.xml
tests="8" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.connectivity.ConnectivityCheckRepositoryImplTest.xml
tests="16" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.connectivity.DefaultConnectivityCheckerTest$Dns.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.connectivity.DefaultConnectivityCheckerTest$Port.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.connectivity.DefaultConnectivityCheckerTest$Tls.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.DohResponseParserTest.xml
tests="13" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.EndpointConnectGateTest.xml
tests="10" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.EndpointRefreshPolicyTest.xml
tests="10" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.EndpointResolverTest.xml
tests="22" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.EndpointStateMachineTest.xml
tests="18" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.EndpointUrlFactoryTest.xml
tests="23" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.HttpsFallbackPolicyTest.xml
tests="19" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.SrvRecordInterpreterTest.xml
tests="18" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.TxtRecordInterpreterTest.xml
tests="31" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.EntityTest$ApplyCompressedStateDiff.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.EntityTest$ControlGroups.xml
tests="12" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.EntityTest$Deserialization.xml
tests="6" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.EntityTest$DisplayAttributes.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.EntityTest$DomainProperty.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.EntityTest$FriendlyNameProperty.xml
tests="6" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.EntityTest$GetIcon.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.EntityTest$SupportsFeature.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.FriendlyStateTest.xml
tests="26" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.display.AlarmDisplayTest.xml
tests="6" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.display.EntitiesForDisplayManagerTest.xml
tests="18" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.display.EntityDisplayWithContextTest.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.impl.IntegrationRepositoryImplTest.xml
tests="11" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.integration.impl.entities.IntegrationRequestTest.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.keychain.KeyChainRepositoryImplTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.network.NetworkChangeObserverImplTest.xml
tests="6" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.network.NetworkStatusMonitorImplTest.xml
tests="9" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.network.WifiHelperImplTest.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.prefs.PrefsRepositoryImplTest$AllowedTags.xml
tests="6" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.prefs.PrefsRepositoryImplTest$Changelog.xml
tests="9" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.prefs.PrefsRepositoryImplTest$GestureActions.xml
tests="9" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.prefs.PrefsRepositoryImplTest$GetScreenOrientation.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.prefs.PrefsRepositoryImplTest$Migration.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.prefs.PrefsRepositoryImplTest$SettingFlows.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.prefs.PrefsRepositoryImplTest$ZoomSettingsFlow.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.prefs.WearPrefsRepositoryImplTest.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.servers.ConnectionAvailabilityMonitorTest.xml
tests="8" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.servers.ServerManagerImplTest$ActivateServerTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.servers.ServerManagerImplTest$AddServerTest.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.servers.ServerManagerImplTest$GetServerByIdTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.servers.ServerManagerImplTest$IsRegisteredTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.servers.ServerManagerImplTest$RemoveServerTest.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.servers.ServerManagerImplTest$ServersFlowTest.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.servers.ServerManagerImplTest$ServersTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.servers.ServerManagerImplTest$UpdateServerTest.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.WebSocketCoreImplTest$Connect.xml
tests="25" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.WebSocketCoreImplTest$Ping.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.WebSocketCoreImplTest$Restoration.xml
tests="13" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.WebSocketCoreImplTest$SendBytes.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.WebSocketCoreImplTest$SendMessage.xml
tests="14" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.WebSocketCoreImplTest$Shutdown.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.WebSocketCoreImplTest$SubscribeTo.xml
tests="9" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.entities.AreaRegistryResponseTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.entities.CompressedEntityTest.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.entities.ConversationResponseTest.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.entities.FloorRegistryResponseTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.entities.NextNamePartTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.websocket.impl.entities.SocketResponseTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.sensors.AudioSensorManagerTest.xml
tests="10" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.sensors.BluetoothSensorManagerTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.sensors.SensorRepositoryImplTest.xml
tests="27" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.sensors.SensorUpdaterNotificationTest.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.sensors.SensorUpdaterTest.xml
tests="9" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.AppNotifChannelsTest.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.AppVersionTest.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.AudioUrlPlayerTest.xml
tests="9" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.BroadcastReceiverExtTest.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.CheckLocalNetworkPermissionUseCaseTest.xml
tests="7" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.FailFastTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.IntentExtTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.JsonUtilTest.xml
tests="57" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.LifecycleExtTest.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.LocalNetworkPermissionWarningTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.MdiIconsTest.xml
tests="8" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.MessagingTokenTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.PlatformTestSupportJUnit4Test.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.PlatformTestSupportJUnitJupiterTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.PlatformTestSupportRobolectricTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.ResyncRegistrationWorkerTest.xml
tests="6" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.SingleSlotQueueTest.xml
tests="10" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.VoiceAudioRecorderTest$Audio-recording-lifecycle.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.VoiceAudioRecorderTest$Downsampling.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.VoiceAudioRecorderTest$Shared-flow-behavior.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.util.VoiceAudioRecorderTest$toAudioBytes-conversion.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.sensor.SensorDaoRoomTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.sensor.SensorDaoTest.xml
tests="6" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.server.ServerConnectionInfoTest$CanUseCloud.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.server.ServerConnectionInfoTest$HasAtLeastOneUrl.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.server.ServerConnectionInfoTest$HasHomeNetworkSetup.xml
tests="7" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.server.ServerConnectionInfoTest$HasPlainTextUrl.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.server.ServerConnectionInfoTest$IsKnownUrl.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.server.ServerConnectionInfoTest$IsRegistered.xml
tests="6" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.server.ServerTest$FriendlyName.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.server.ServerTest$FromTemporaryServer.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.server.ServerTest$Version.xml
tests="3" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.database.widget.TodoWidgetEntityTest.xml
tests="4" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.di.DataModuleTest.xml
tests="2" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.sensors.SensorManagerCompletenessTest.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.sensors.SensorManagerTest.xml
tests="1" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.util.FlowUtilTest.xml
tests="5" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.util.UriExtensionsTest.xml
tests="16" skipped="0" failures="0" errors="0"
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.util.UrlUtilTest.xml
tests="66" skipped="0" failures="0" errors="0"
app/build/test-results/testMinimalDebugUnitTest/TEST-io.homeassistant.companion.android.onboarding.manualserver.ManualServerViewModelTest.xml
tests="3" skipped="0" failures="0" errors="0"
app/build/test-results/testMinimalDebugUnitTest/TEST-io.homeassistant.companion.android.util.IgnoreViolationRulesTest.xml
tests="4" skipped="0" failures="0" errors="0"
```

### 解析模块测试类

```text
common/build/test-results/testDebugUnitTest/binary/results-generic.bin
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.DohResponseParserTest.xml
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.EndpointConnectGateTest.xml
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.EndpointRefreshPolicyTest.xml
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.EndpointResolverTest.xml
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.EndpointStateMachineTest.xml
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.EndpointUrlFactoryTest.xml
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.HttpsFallbackPolicyTest.xml
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.SrvRecordInterpreterTest.xml
common/build/test-results/testDebugUnitTest/TEST-io.homeassistant.companion.android.common.data.endpoint.TxtRecordInterpreterTest.xml
```

## 12. 结论

- 通过：APK 已生成；包名、标签与启动入口符合预期；合并后清单中不存在 `Leaks` 启动入口；解析模块存在于 DEX 中。
