# 闪退根因分析：改造版 Home Assistant Android 在 Redmi K70 Ultra 上点击即退出

**设备**：红米 K70 至尊版（联发科天玑 9400，HyperOS）
**包名**：`io.homeassistant.companion.android.minimal.debug`
**现象**：应用可启动并停留在欢迎页；点击「连接我的 HA 服务器」（`welcome_connect_to_ha`）
或「更多信息」（`welcome_learn_more`）进程即退出，logcat 出现
`System.exit called, status: 1` 与 `!!! CRITICAL FAILURE: FAIL-FAST !!!`。

**结论先行**：这不是 TXT/SRV 解析改造引入的问题，而是 **debug 构建自带的 FailFast 严格模式
在联发科厂商组件产生违规时把进程杀掉**。该行为来自上游 Home Assistant Android 的官方代码，
本次改造没有改动任何相关文件；但官方从不把 debug 构建当日常应用分发，所以这个坑在改造版上
第一次被真实使用暴露出来。

---

## 1. 证据链（逐步可复核）

### 1.1 全仓库只有一个地方会调用 `System.exit`

```bash
grep -rn "exitProcess\|System.exit" --include=*.kt .
```

```text
common/src/main/kotlin/.../common/util/FailFastHandlers.kt:39:        exitProcess(1)
```

唯一一处。`FailFastHandlers.kt` 中：

* `CrashFailFastHandler.handleException()` → 打印 `!!! CRITICAL FAILURE: FAIL-FAST !!!` → `exitProcess(1)`
* `LogOnlyFailFastHandler.handleException()` → 只打印，不退出

哪一个被用作默认值由**构建变体**决定：

```kotlin
// common/src/debug/.../DefaultFailFastHandler.kt
val DefaultFailFastHandler = CrashFailFastHandler        // debug → 退出

// common/src/release/.../DefaultFailFastHandler.kt
val DefaultFailFastHandler = LogOnlyFailFastHandler      // release → 只记录
```

**这就是 `System.exit called, status: 1` 的唯一来源**，也直接解释了为什么只出现在 debug 包上。

### 1.2 谁会调用 `FailFast.failWith(violation)`

```kotlin
// common/src/main/kotlin/.../common/util/HAStrictMode.kt
StrictMode.setThreadPolicy(
    StrictMode.ThreadPolicy.Builder()
        .detectAll()
        .penaltyListener(Executors.newSingleThreadExecutor()) { violation ->
            if (!threadPolicyIgnoredViolationRules.any { it.shouldIgnore(violation) }) {
                FailFast.failWith(violation)          // ← 没有规则豁免 → 退出
            } else {
                Timber.w(violation, "Ignoring unexpected violation ($violation)")
            }
        }
        .build(),
)
```

启用条件（`HomeAssistantApplication.onCreate()`）：

```kotlin
if (SdkVersion.isAtLeast(Build.VERSION_CODES.S) &&
    BuildConfig.DEBUG &&
    !BuildConfig.NO_STRICT_MODE
) { HAStrictMode.enable(...) }
```

* `NO_STRICT_MODE` 来自 `AndroidApplicationConventionPlugin.kt:51-53`，默认 `false`（未传属性时）
* K70 至尊版 HyperOS 基于 Android 14/15 → `SDK >= S` 成立
* 本包是 debug 变体 → `BuildConfig.DEBUG == true`

三个条件同时成立 → `detectAll()` + FailFast 生效。**该策略不含 `penaltyDeath`**，
全仓库也没有任何 `penaltyDeath`；致命性来自 `penaltyListener` → `FailFast` → `exitProcess(1)`。

### 1.3 触发违规的是联发科厂商组件，不是应用代码

logcat 中致命违规的堆栈：

```text
android.os.strictmode.DiskReadViolation
  java.io.File.exists()
  com.mediatek.boostfwk.utils.Util.isGameApp()
  com.mediatek.boostfwk.identify.scroll.ScrollIdentify.checkAppType()
  com.mediatek.boostfwk.BoostModuleDispatcher...
  android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent()
```

要点：

* 违规点 `File.exists()` 位于 **`com.mediatek.boostfwk`**（MediaTek boost framework，
  厂商性能调度框架），是触摸事件管线在识别「该应用是否为游戏」时做的探测。
* 它运行在应用主线程上（`ViewPostImeInputStage.processPointerEvent`），
  StrictMode 的线程策略会跨 binder 传播，所以这次磁盘读取被记到应用头上。
* 应用既不能改这段代码，也无法避免它 —— 与既有豁免规则注释中描述的
  Samsung / MIUI / Android Auto 情形完全同类。

### 1.4 为什么这个违规没有被豁免（根因落点）

`app/src/main/kotlin/.../util/IgnoreViolationRules.kt` 现有 13 条线程策略豁免规则，
按 `className` / `methodName` 精确匹配：

| 现有规则 | 匹配条件 | 是否命中 boostfwk 堆栈 |
|---|---|---|
| IgnoreChangelogDiskRead | `info.hannes.changelog.ChangeLog` | 否 |
| IgnoreNotificationHistoryFragmentLoadSharedPrefDiskRead | 本应用 Fragment | 否 |
| IgnoreComposeTextContextMenuDiskRead | Compose 文本菜单 | 否 |
| IgnoreActivityThreadVsyncDiskReadWrite | `ActivityThread.scheduleVsyncSS` | 否 |
| IgnoreSamsungInputRuneDiskRead | `com.samsung.android.rune.InputRune` | 否 |
| IgnoreSamsungKnoxProKioskDiskRead | Samsung Knox | 否 |
| IgnoreSamsungSpegDiskRead | `methodName.startsWith("isSpeg")` | 否（`isGameApp` 不匹配） |
| IgnoreAndroidAutoServiceConnectionDiskRead | Android Auto | 否 |
| IgnoreAndroidAutoRendererServiceDiskRead | Android Auto | 否 |
| IgnoreMiuiFontSettingsDiskRead | `miui.util.font.FontSettings` | 否 |
| IgnoreMiuiTurboSchedMonitorDiskRead | `android.os.TurboSchedMonitorImpl` | 否 |
| IgnoreChromiumKeyStoreDiskWrite | KeyStore + chromium | 否 |
| IgnoreAppCompatPersistLocalesDiskReadWrite | AppCompat 三帧同时出现 | 否 |

**没有任何规则覆盖 `com.mediatek.boostfwk.*`** → `FailFast.failWith(violation)` → 退出。

这一步已写成可执行的回归测试（见 §3），测试里把 logcat 中的真实帧喂给全部 13 条规则，
断言零命中。

### 1.5 logcat 中「Ignoring unexpected violation」并不矛盾

```text
HAStrictMode: Ignoring unexpected violation (android.os.strictmode.DiskReadViolation)
```

这行来自 `HAStrictMode.kt:44/57` 的**豁免分支**，tag 是 `HAStrictMode`；
而致命路径的日志 tag 是 `CrashFailFastHandler`。两者是**两次不同的违规**：

* 启动时 AppCompat 的 `persistLocales` 磁盘读 → 被
  `IgnoreAppCompatPersistLocalesDiskReadWrite` 命中 → **豁免**（就是这行日志）；
* 触摸时联发科 boostfwk 磁盘读 → 无规则命中 → **致命**（`System.exit(1)`）。

所以应用能正常启动、正常渲染欢迎页，却在第一次触摸时退出 —— 与现象完全吻合。
两个按钮都在同一个 `WelcomeScreen`（`app/src/main/kotlin/.../onboarding/welcome/WelcomeScreen.kt`），
本质上是「这一屏的任何触摸都会退出」，而不是两个按钮各自有独立的 bug。

### 1.6 官方原版 vs 改造版：实际差异核对

逐文件核对改造基线 `pre-resolver-refactor` 到 HEAD 的全部 60 个改动：

| 关注点 | 文件 | 改造是否触碰 |
|---|---|---|
| 严格模式启用 | `HAStrictMode.kt` | **否**（上游原样） |
| 致命退出 | `FailFastHandlers.kt` | **否** |
| debug/release 默认 handler | `common/src/{debug,release}/.../DefaultFailFastHandler.kt` | **否** |
| 豁免规则 | `IgnoreViolationRules.kt` | **否** |
| 严格模式开关 | `AndroidApplicationConventionPlugin.kt`（`NO_STRICT_MODE`） | **否** |
| debug 图标 | `app/src/debug/res/mipmap-anydpi-v26/ic_launcher.xml` | **否** |
| Application 初始化 | `HomeAssistantApplication.kt` | 是，但只新增了动态地址刷新的生命周期注册，未触碰 StrictMode 段 |
| 约定插件 | `AndroidCommonConventionPlugin.kt` | 是，但只删除了 LeakCanary 的 `debugImplementation`（这是上一阶段为修 `LockOutOfDateException` 做的），与 StrictMode 无关 |

**结论：闪退路径与本次改造完全无关。** 差异在于「debug 包被当日常应用使用」：
官方从不下发 debug 构建，所以这个在上游同样存在的坑从未被普通用户踩到。

### 1.7 TXT/SRV 改造是否另有独立问题（要求 6）

排查结论：**没有发现会引发该闪退的问题，也没有发现破坏原版功能的问题。**

* **无主线程磁盘/网络 I/O**。改造新增的全部持久化都走 `LocalStorageImpl`
  （`common/src/main/kotlin/.../LocalStorageImpl.kt`），每个方法都包在
  `withContext(Dispatchers.IO) { ... }` 里。`EndpointSettingsStore` 只有 `suspend` 接口。
  `EndpointForegroundRefresher` 的循环跑在 `Dispatchers.Default`，
  Application 的 `scheduleEndpointBackgroundRefresh()` 跑在 `Dispatchers.IO`。
* **固定地址模式不受影响**。`EndpointResolutionEngine.preConnect()` 对
  `mode == FIXED` 直接返回 `ConnectDecision.PROCEED`，而 `EndpointSettings` 的默认模式就是
  `FIXED`（`EndpointModel.kt:192`），未配置过该功能的用户在登录前门禁处被直接放行。
* **onboarding 的 `txt://`/`srv://` 解析在 `Dispatchers.IO` 上执行**
  （`ManualServerViewModel.resolveRecord()` 用 `withContext(Dispatchers.IO)`）。
* 登录前门禁的异常处理把 `CancellationException` 原样重抛，
  门禁自身异常时回退为「继续用已配置地址」，不会把网络抖动变成连接失败。

### 1.8 图标：红色不是被改坏的，是上游 debug 构建的既定约定

实测对比（两个 PNG 已逐字节比对）：

| 源集 | 图标 | 背景色 |
|---|---|---|
| `app/src/main/res` | 淡蓝底 + 白色 HA 房子 | `#18BCF2`（`common/.../values/colors.xml` 的 `colorLauncherBackground`） |
| `app/src/debug/res` | **红底** + 白色 HA 房子 | `@android:color/holo_red_dark` |

`app/src/debug/res/mipmap-anydpi-v26/ic_launcher.xml` 的内容与 **上游 main 分支逐字一致**
（已抓取 `https://raw.githubusercontent.com/home-assistant/android/main/app/src/debug/res/mipmap-anydpi-v26/ic_launcher.xml` 比对）。
改造的全部 60 个改动文件里没有任何 `mipmap` / `launcher` / `icon` / `colors.xml`。

因此：**debug 包显示红色图标是上游刻意为之**（让开发包一眼可辨），属于
「项目中有明确的自定义图标要求」这一例外情形，本次**未改动**。
要拿到淡蓝色官方图标，安装 **release** 变体即可；若确需 debug 包也用淡蓝图标，
把 `app/src/debug/res/mipmap-anydpi-v26/ic_launcher*.xml` 的 background 改回
`@color/colorLauncherBackground`（并替换 `app/src/debug/res/mipmap-*/ic_launcher*.png`）即可，
一行配置的事，等确认后我再动手。

---

## 2. 修复内容

### 2.1 新增一条联发科豁免规则（最小化，不关闭其它检查）

文件：`app/src/main/kotlin/io/homeassistant/companion/android/util/IgnoreViolationRules.kt`

* `threadPolicyIgnoredViolationRules` 增加 `IgnoreMediatekBoostFwkDiskRead`；
* 新增规则对象，只匹配 `DiskReadViolation` 且堆栈中存在
  `className.startsWith("com.mediatek.boostfwk.")` 的帧。

这与仓库里既有的 Samsung（3 条）、MIUI（2 条）、Android Auto（2 条）OEM 豁免规则是同一模式、
同一粒度：**只豁免「发生在应用无法控制的厂商组件里」的违规**。
应用自身代码的磁盘读/写、网络访问、自定义慢调用等检查全部保留，仍然致命。

### 2.2 顺带修复一个被暴露的真实缺陷

`app/src/test/.../onboarding/manualserver/ManualServerViewModelTest.kt` 无法编译：

```text
ManualServerViewModelTest.kt:25:21 No value passed for parameter 'dnsClient'.
ManualServerViewModelTest.kt:25:21 No value passed for parameter 'endpointProbe'.
```

上一阶段把 `ManualServerViewModel` 的构造函数从无参改为注入 `DnsClient` + `EndpointProbe`，
但该测试没同步；由于当时只跑过 `:common:testDebugUnitTest`，从未编译过 `:app` 的测试源集，
所以一直没被发现。**这正是 CI 里 `:app:testMinimalDebugUnitTest` 那一步会红的原因**
（它在工作流里是 `continue-on-error`，所以之前没把问题顶出来）。
已补上两个 relaxed mock。

> 附带说明：`DiskReadViolation` 的构造函数在 SDK stub 里是包级私有的，
> 所以回归测试用 mockk 构造违规对象并注入 logcat 中的真实堆栈帧。

---

## 3. 新增测试

`app/src/test/kotlin/io/homeassistant/companion/android/util/IgnoreViolationRulesTest.kt`
（4 个用例，直接使用生产代码里的 `threadPolicyIgnoredViolationRules` 列表）：

| 用例 | 断言 | 作用 |
|---|---|---|
| `Mediatek boost framework disk read is ignored` | 任一规则命中 logcat 真实帧 | 证明修复生效 |
| `Disk read from the app's own code is still fatal` | 无规则命中应用自身代码的磁盘读 | 证明没有一刀切关闭检查 |
| `Exactly one rule covers the boost framework violation` | 命中数 == 1 | 防止将来误加重复规则 |
| `Boost framework rule does not ignore other violation types` | 非 `DiskReadViolation` 不命中 | 限定规则作用域 |

实测：`tests=4 failures=0 errors=0 skipped=0`。

---

## 4. 执行记录

| 步骤 | 命令 | 结果 |
|---|---|---|
| 修复后单测（定点） | `:app:testMinimalDebugUnitTest --tests IgnoreViolationRulesTest --tests ManualServerViewModelTest` | `BUILD SUCCESSFUL in 7m 37s`，7/7 通过 |
| 全量回归 | `:common:testDebugUnitTest` | 见 §4.1 |
| 全量回归 | `:app:testMinimalDebugUnitTest` | 见 §4.1 |
| 新 APK | `:app:assembleMinimalDebug` | 见 §4.1 |
| 静态检查 | `bash .github/scripts/apk-static-inspection.sh` | 见 §4.1 |

### 4.1 结果（实测）

| 项 | 值 |
|---|---|
| 定点单测 | `:app:testMinimalDebugUnitTest --tests IgnoreViolationRulesTest --tests ManualServerViewModelTest` → `BUILD SUCCESSFUL in 7m 37s`，`IgnoreViolationRulesTest` 4/4、`ManualServerViewModelTest` 3/3，failures=errors=0 |
| 新 APK | `:app:assembleMinimalDebug` → `BUILD SUCCESSFUL`，`75,670,931 字节`，SHA-256 `12407d556823364cb83580c8b6331025ec4ddb6ecd8bf32f117025dba10c4d15` |
| 新规则入包 | `classes7.dex` 中可检索到 `IgnoreMediatekBoostFwkDiskRead`（6 次）与 `com.mediatek.boostfwk`（1 次）字面量 |
| 静态检查 | `apk-static-inspection.sh` → `exit=0`，结论「通过」；包名/标签/启动入口不变，合并后清单无 `Leaks` |
| 提交 | `55bf17e` |

### 4.2 全量 `:app:testMinimalDebugUnitTest` 在本机无法完成

首次尝试把三个任务串在一起跑（`:common:testDebugUnitTest` → `:app:testMinimalDebugUnitTest` →
`:app:assembleMinimalDebug`），结果 **测试 JVM 挂起**：

* 运行 1 小时后，测试进程 CPU 时间停在 `767.9s` 不再增长（约 15 分钟无变化）；
* `/tmp/` 下出现多个 `robolectric-no-test-yet<随机数>` **空目录**（14:05 / 14:27 / 14:28），
  说明 Robolectric 反复尝试建立沙箱但没有任何测试跑完；
* 50 分钟内 `app/build/test-results/` **没有新增任何 XML**（Gradle 是按类即时写结果的）。

判断为本机环境问题（Robolectric 4.17 沙箱在 Windows + JDK 21 + 该代理网络下死锁），
不是代码问题 —— 定点跑同类测试是正常的。处理方式：终止挂起进程，改跑定点测试 + 构建。
**`:app` 全量单测因此未在本机执行**；这套测试此前甚至无法编译（见 §2.2），
在工作流里也是 `continue-on-error` 的参考步骤。

---

## 5. 无法验证 / 未验证的内容

**诚实声明，以下均未完成：**

1. **未在真实设备或模拟器上复现/验证。**
   * 本机没有联发科设备，而 `com.mediatek.boostfwk` 是厂商私有代码，**模拟器根本无法复现**
     这个违规 —— 这是判断「无法复现」的技术原因，不是借口。
   * 本机 verification 工作区里的 Android SDK 只有 `platforms`、`build-tools`、`platform-tools`、
     NDK、CMake，**没有 `system-images` 与 `emulator`**，也没有可用的硬件加速虚拟化，
     因此连「应用启动 + 三个入口 + TXT/SRV/固定地址」的模拟器冒烟测试都无法执行。
2. **修复效果未被设备验证。** 规则是否覆盖该机型上 *所有* 会致命的厂商违规，
   只有装上新包真机试了才知道。若仍退出，请按 §6 取回完整崩溃日志给我。
3. **三个入口（应用启动 / 连接我的 HA 服务器 / 更多信息）未在设备上实测。**
4. **TXT / SRV / 固定地址三种模式的实机联通性未验证**，只有 164 个解析模块单测 +
   `ManualServerViewModelTest` 3 个固定地址用例作为代码层证据。
5. **`Logs` 页能否读到崩溃内容未验证**（见 §6）。
6. **GitHub Actions 服务端仍未运行**（本机无 `gh` / token，与上一份报告一致）。

---

## 6. 请帮我拿到这份日志（用于最终确认）

应用自己会把未捕获异常写到磁盘，位置是：

```text
/data/data/io.homeassistant.companion.android.minimal.debug/cache/fatalcrash/last_crash
```

（`app/src/main/kotlin/.../util/CrashSaving.kt` 顶部注释写明了该路径。）

两种取法任选：

1. **在应用里看**：新包装上后若仍闪退，重启应用 → 设置 → 日志（`LogFragment` 会读这个文件，
   只保留 12 小时内的内容）。
2. **adb 拉取**：
   ```bash
   adb shell "run-as io.homeassistant.companion.android.minimal.debug cat cache/fatalcrash/last_crash"
   ```

拿全量 logcat 时请重点找 **tag 为 `CrashFailFastHandler`** 的那几行 —— 它带
`!!! CRITICAL FAILURE: FAIL-FAST !!!` 横幅和**完整**违规堆栈，
能一次性确认致命违规到底来自哪个组件。

---

## 7. 备选方案（已评估，未采用）

| 方案 | 为什么不采用 |
|---|---|
| `./gradlew assembleMinimalRelease` 出 release 包 | release 下 `DefaultFailFastHandler = LogOnlyFailFastHandler`，且 `BuildConfig.DEBUG=false` 根本不启用严格模式，确实不闪退；但这等于**放弃**了 debug 构建的价值，且没解释根因 |
| 传 `-PnoStrictMode=true` | 一刀切关掉全部严格模式检查，违反「不要直接关闭所有 StrictMode 检查」的要求 |
| 在 `HAStrictMode` 里 catch 掉所有异常 / 换成 `LogOnlyFailFastHandler` | 吞掉异常，违反「不要吞掉所有异常」的要求，也会让上游所有 OEM 豁免规则失去意义 |
| 只匹配 `isGameApp` 这一个方法 | 太窄。boostfwk 其它模块（`identify`、`BoostModuleDispatcher`）同样可能触发，按包前缀匹配与既有 OEM 规则粒度一致 |
