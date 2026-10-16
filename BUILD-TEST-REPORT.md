# 构建与测试报告

**对象**：Home Assistant Android 客户端 —— TXT/SRV 动态地址解析、自动生成访问链接、定时刷新、登录前强制校验
**基线**：`git tag pre-resolver-refactor`（= `58bb6a5`）
**结论提交**：`4581185`
**报告口径**：本文所有数字均来自**真实执行过的命令**。未执行的内容在 §7 明确标注为「未验证」，不做推断式宣称。

---

## 1. 环境

| 项目 | 值 |
|---|---|
| JDK | Zulu OpenJDK **21.0.8** (`21.0.8+9-LTS`) |
| Gradle | **9.8.0** 发行版（直接调用，非 wrapper） |
| Android SDK 平台 | `platforms;android-37.0` |
| Android Build-Tools | `build-tools;37.0.0` |
| Android NDK | **r29** `29.0.14206865`（与 `gradle/libs.versions.toml` 一致） |
| CMake | 4.1.2 |
| Maven 仓库 | 阿里云镜像（`cn-mirrors.init.gradle.kts`） |
| 操作系统 | Windows 10 (x64) |
| AGP / Kotlin | 9.4.1 / 2.4.20 |

> 说明：`./gradlew` 在此网络下从 `services.gradle.org` 下载发行包被限速到约 30 KB/s，
> 因此本机直接调用已下载并校验过 sha256 的 Gradle 9.8.0 发行版。校验值与
> `gradle/wrapper/gradle-wrapper.properties` 中 `distributionSha256Sum` 一致：
> `bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c`。

---

## 2. 单元测试（真实 Gradle 运行）

```bash
./gradlew :common:testDebugUnitTest
```

```text
BUILD SUCCESSFUL in 3m 15s
71 actionable tasks: 1 executed, 70 up-to-date
gradle exit=0
```

### 2.1 汇总

| 指标 | 实测值 |
|---|---|
| JUnit XML 结果文件 | 142 |
| **测试总数** | **1070** |
| failures | **0** |
| errors | **0** |
| skipped | **0** |

### 2.2 动态地址解析模块（本次新增，9 个测试类 / 164 个用例）

| 测试类 | 用例数 |
|---|---|
| `TxtRecordInterpreterTest` | 31 |
| `EndpointUrlFactoryTest` | 23 |
| `EndpointResolverTest` | 22 |
| `HttpsFallbackPolicyTest` | 19 |
| `EndpointStateMachineTest` | 18 |
| `SrvRecordInterpreterTest` | 18 |
| `DohResponseParserTest` | 13 |
| `EndpointConnectGateTest` | 10 |
| `EndpointRefreshPolicyTest` | 10 |
| **合计** | **164** |

### 2.3 受影响的既有测试

| 测试类 | 用例数 | 说明 |
|---|---|---|
| `WebSocketCoreImplTest` | 71 | 70 个原有 + 1 个新增 `DynamicAddressGate`（门禁拒绝必须阻止连接） |

### 2.4 首次运行失败与修复（重要）

第一次真实运行 **67 个失败**，全部集中在 `WebSocketCoreImplTest`。根因不是产品代码错，而是测试替身：

```text
mockk<ServerManager>(relaxed = true)
  → ConnectGateResult.mayConnect 是 Boolean 属性
  → relaxed mock 对 Boolean 取值器返回 false
  → 每次连接都被门禁拦下 → 67 个用例失败
```

修复：新增 `permissiveServerManager()` 显式桩住
`endpointRepository.ensureFreshBeforeConnect()` 返回 `ConnectDecision.PROCEED`。
已提交为 `34cfa66`。

**意义**：这一批失败只会在真实 Gradle 运行时出现，本机「纯 Kotlin 逻辑子集」验证不会暴露它。
这也是「不能声称通过未实际执行的测试」这条要求的具体体现。

另有一次失败是 **构建产物的报告目录无法删除**
（`IOException: Couldn't delete ...\build\reports\tests\...html`，Windows 文件占用），
与代码无关，重跑即成功。

---

## 3. APK 构建

```bash
./gradlew :app:assembleMinimalDebug
```

```text
BUILD SUCCESSFUL in 4m 46s
116 actionable tasks: 20 executed, 96 up-to-date
gradle exit=0
```

| 项目 | 值 |
|---|---|
| 产物路径 | `app/build/outputs/apk/minimal/debug/app-minimal-debug.apk` |
| 大小 | **75,536,735 字节** |
| SHA-256 | `30d84fffac9b9fa53d3ef8ce0e97fb293820fa02f368319cce9aad58e7e65238` |

### 3.1 构建过程中的两个环境级障碍（已解决，供复现参考）

**(a) NDK 解压静默截断。** 把 NDK 解压到超长路径时，Windows 260 字符经典路径上限导致
`toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr` 整棵子树丢失（1.5 GB 而非 2.4 GB）。
症状：

```text
ld.lld: error: cannot open crtbegin_dynamic.o: No such file or directory
ld.lld: error: unable to find library -latomic
ld.lld: error: cannot open .../lib/clang/21/lib/armv7-none-linux-android23/libclang_rt.builtins.a
```

解决：改用 Python `zipfile` 逐项解压（**逐项报错，不静默跳过**）到短根目录，得到
9269 个成员 / 0 失败 / 2.4 GB，再移动到位。

**(b) CMake `FetchContent` 下载失败。** 经本机出口代理拉取时返回
`status_code: 16 "Error in the HTTP2 framing layer"`，而同一 URL 用 `curl` 直取返回 200。
解决：预先用 `curl` 下载并校验 SHA-256，把归档放到
`<module>/.cxx/Debug/<hash>/<abi>/_deps/<name>-subbuild/<name>-populate-prefix/src/`，
覆盖全部 4 个 ABI（共 5 个依赖 × 4 ABI = 20 个归档）。
`FetchContent` 生成的 `download-<name>-populate.cmake` 在目标文件已存在且哈希正确时会跳过下载。

---

## 4. APK 静态检查

```bash
bash .github/scripts/apk-static-inspection.sh
```

```text
script exit=0
```

报告：`diagnostics/apk-static-inspection-report.md`，结论为 **通过**。

### 4.1 核实到的事实

| 检查项 | 结果 |
|---|---|
| 包名 | `io.homeassistant.companion.android.minimal.debug` |
| `versionName` | `0.0.1-SNAPSHOT-minimal` |
| `sdkVersion` / `targetSdkVersion` | `23` / `37` |
| 应用标签 | `Home Assistant`（全部 90+ 语言一致） |
| 启动 Activity | `io.homeassistant.companion.android.launch.LaunchActivity` |
| 原生库 ABI | `arm64-v8a` `armeabi-v7a` `x86` `x86_64` |
| 源码清单 `MAIN` 条目数 | **恰好 3**（`LAUNCHER` 第 122 行、`LEANBACK_LAUNCHER` 第 127 行、`HOME` 别名第 143 行） |
| **`Leaks` 启动入口** | **不存在**（合并后清单与 APK 归档条目均无匹配） |
| 解析模块入包 | `classes*.dex` 中存在 `io/homeassistant/companion/android/common/data/endpoint/` 类型描述符 |
| 脚本硬性失败条件 | 0 项触发（`FAILURES=0`） |

### 4.2 `Leaks` 图标的来源（结论）

* 源码中**从未**存在 `Leaks` 入口。
* `Leaks` 来自被合并进来的 **LeakCanary AAR 清单**：其
  `leakcanary.internal.activity.LeakActivity` 自带 `MAIN` + `LAUNCHER` 过滤器，标签即 `Leaks`。
* 该依赖原本通过 `debugImplementation` 进入应用，现已在约定插件中移除声明，
  入口随之消失。检查脚本对合并后清单做**硬性断言**，一旦回来就判定失败。

---

## 5. 依赖锁定报错的根因与修复（实证，非猜测）

### 5.1 报错

```text
org.gradle.internal.locking.LockOutOfDateException:
Did not resolve 'com.squareup.leakcanary:shark:2.14' which is part of the dependency lock state
```

### 5.2 根因

* `gradle.properties` 中 `#noLeakCanary=true` 被**注释掉** → 约定插件
  `AndroidCommonConventionPlugin.kt` 视属性缺失为 `false` → 包含 LeakCanary。
* 提交在库里的锁状态正是在「包含」配置下解析的。
* 但 CI 传了 `-PnoLeakCanary=true` → 该配置**排除** LeakCanary
  → 依赖图与锁状态不一致 → 抛异常。

一句话：**不是锁文件坏了，而是「解析锁文件的配置」和「实际构建的配置」不是同一个。**

### 5.3 关键实证：为什么「跑一次 dependencies 报告」查不出来

`LockOutOfDateException` 只在**真正解析**时抛出。宽松报告任务看不出问题：

```bash
./gradlew :app:dependencies --configuration minimalDebugRuntimeClasspath
```

```text
16 行 `<module> FAILED`（shark:2.14、shark-graph、shark-hprof、shark-log、shark-android、
                        leakcanary-android*、leakcanary-object-watcher*、plumber-android*、curtains:1.2.4）
BUILD SUCCESSFUL
gradle exit=0        ← 退出码是 0！
```

用专门的 init script 做真实 `Configuration.resolve()` 才复现出原始报错：

```text
RESOLVE FAILED :app:minimalDebugRuntimeClasspath
  cause[1] LockOutOfDateException: Did not resolve 'com.squareup.leakcanary:shark-graph:2.14' ...
RESOLVE FAILED :app:minimalDebugCompileClasspath
  cause[1] LockOutOfDateException: Did not resolve 'com.squareup.leakcanary:leakcanary-object-watcher:2.14' ...
RESOLVE FAILED :app:minimalDebugUnitTestRuntimeClasspath
  cause[1] LockOutOfDateException: Did not resolve 'com.squareup.leakcanary:shark-hprof:2.14' ...
```

### 5.4 修复

1. `AndroidCommonConventionPlugin.kt` 删除 `debugImplementation(libs.leakcanary.android)`
   条件声明与 `noLeakCanary` 分支。
2. `gradle/libs.versions.toml` 删除已无引用的 `leakcanary-android` 别名；
   保留 `leakcanary-android-instrumentation`（仅 `androidTest` 源集，不进应用 APK）。
3. `gradle.properties` 删除 `#noLeakCanary=true` 并写入根因说明。
4. 按 Gradle 官方做法**重新解析并回写**锁文件（**没有删除锁文件、没有忽略错误**）：

```bash
./gradlew --write-locks --no-configuration-cache resolveAndLockAll
# BUILD SUCCESSFUL in 2m 51s        gradle exit=0
```

回写差异**只涉及 LeakCanary 一族**（逐行核对确认）：

| 锁文件 | 变化 | 说明 |
|---|---|---|
| `wear/gradle.lockfile` | −16 行 | LeakCanary 条目全部移除 |
| `app/gradle.lockfile` | +12 / −16 | 同上 |
| `automotive/gradle.lockfile` | +12 / −16 | 同上 |
| `gradle.lockfile`、`common`、`microwakeword`、`provides-sensor-processor`、`settings-gradle`、`testing-unit` | 0 变化 | 无 LeakCanary 条目 |

彻底移除的模块：`leakcanary-android`、`leakcanary-object-watcher-android`、
`plumber-android`、`plumber-android-core`。
残留的 12 条只挂在 `fullDebugAndroidTest*` / `minimalDebugAndroidTest*` 上，
对应 `AndroidApplicationDependenciesConventionPlugin.kt:102` 的
`androidTestImplementation(libs.leakcanary.android.instrumentation)` —— 合法，且不会进入应用 APK。

**收敛后实测**：`FAILED` 计数 = **0**，LeakCanary 计数 = **0**。

### 5.5 为什么最终没有采用「独立任务真解析」这一招

`minimal*` 系列 classpath 带产品风味。从独立任务解析 `:app` 的配置时，Gradle 9.4 解析器会在
`:common`（没有同名风味）上报一个**与锁无关**的错误：

```text
ArtifactSelectionException: ... cannot choose between the following variants of project ':common':
  Configuration ':common:debugRuntimeElements' variant 'android-aar-metadata' ...
      Unmatched attributes:
        Doesn't say anything about com.android.build.api.attributes.ProductFlavor:version (required 'minimal')
```

它会**盖住**真正的锁违规（实测：该 init script 失败时报的是
`The dependency lock state is out of date for 3 configuration(s)`，而底层 cause 是
`ArtifactSelectionException`）。因此
`.github/scripts/verify-dependency-locks.init.gradle.kts` 已被**删除**，工作流改为：

1. 宽松报告 + 断言 ` FAILED$` 行数为 0；
2. 断言 LeakCanary 条目数为 0；
3. **权威解析交给 `:app:assembleMinimalDebug` 本身** —— 它在带风味的真实构建里解析，
   锁状态不一致时必然失败。

---

## 6. GitHub Actions 工作流

文件：`.github/workflows/build-txt-apk.yml`（**完整重写**，不是在旧文件上打补丁）

| # | 步骤 | 说明 |
|---|---|---|
| 1 | `actions/checkout@v4` | — |
| 2 | `actions/setup-java@v4` | Temurin JDK 21 |
| 3 | `gradle/actions/setup-gradle@v4` | `cache-provider: basic`（MIT 缓存，不依赖专有服务） |
| 4 | 校验 Gradle Wrapper | 仓库中 `gradlew` git 模式已修正为 `100755`；此步仅在检出丢失可执行位时兜底 `chmod +x`，并打印 `./gradlew --version` |
| 5 | 安装 Android SDK | `platform-tools`、`platforms;android-37.0`、`build-tools;37.0.0` |
| 6 | 生成 mock `google-services.json` | 不引入真实密钥 |
| 7 | 检查锁状态 | `:app:dependencies` + `FAILED`/LeakCanary 计数双断言（§5.5） |
| 8 | 跑单元测试 | `:common:testDebugUnitTest`（硬性门禁）；`:app:testMinimalDebugUnitTest` 以 `continue-on-error` 作参考 |
| 9 | 编译 APK | `:app:assembleMinimalDebug`（权威解析发生在此） |
| 10 | 静态检查 | `bash .github/scripts/apk-static-inspection.sh` |
| 11 | 上传 | APK、`apk-static-inspection-report.md`、`merged-manifest.txt`、依赖报告、JUnit 报告、失败时构建报告 |

已修正的历史问题：

* 旧工作流传 `-PnoLeakCanary=true` 而锁状态是「包含」态 → 必然 `LockOutOfDateException`。现已在依赖声明层面统一。
* 旧工作流存在 `gradlew` 可执行位问题（仓库模式非 100755）。
* 旧工作流直接依赖 `:app:dependencies` 的**退出码**判断锁状态 —— 该退出码恒为 0，属假通过。

---

## 7. 未验证 / 未执行的事项（诚实声明）

| 事项 | 状态 |
|---|---|
| **GitHub Actions 服务端运行** | **未执行**。本机没有 `gh` CLI，也没有可用 token，无法触发或查询 CI。工作流的正确性来自**逐条步骤本机复现**（§2/§3/§4），不是 CI 已跑绿。 |
| `actions/checkout`、`setup-java`、`setup-gradle`、SDK 安装、artifact 上传 | 未在本机执行（属 CI 专属环节）。 |
| 真机 / 模拟器安装运行 | 未做。仅做静态检查（包名、清单、DEX、ABI）。 |
| 真实 DNS TXT / SRV 记录联通性 | 未做端到端外网验证；解析逻辑由 164 个单元测试覆盖，含 DoH JSON 解析。 |
| `:app:testMinimalDebugUnitTest` | 未在本机运行（工作流中为 `continue-on-error` 的参考步骤）。 |

### 规格中「无法从源码确认」的项（未擅自规定为唯一答案）

1. **TXT 记录的官方格式** —— 无权威定义。实现接受旧代码已支持的多种形态（URL / `host:port` /
   裸主机 / `key=value` 包 / `url=` 覆盖），每种都有测试。
2. **SRV 查询名的服务/协议标签** —— 典型为 `_8123._tcp.<domain>`，但官方未规定固定标签。
   查询名由用户填写，UI 只校验 `_x._y.domain` 形态。
3. **同 Priority 多记录的选取策略** —— RFC 2782 给的是加权随机，也允许更简单的策略。
   默认加权随机，另提供确定性策略供测试使用。
4. **Android 后台精确调度** —— 不可能后台每 30 秒运行一次。`WorkManager` 周期任务下限 15 分钟，
   Doze / App Standby 下更晚。前台按配置精确刷新，后台遵守系统限制，UI 显示实际行为，
   **登录前校验不受调度影响**。
5. **明文降级默认值** —— 默认 `allowHttpFallback = false`、`preferHttps = true`（最安全），
   是否允许交用户在设置页决定。
6. **`compileSdk` 平台包名** —— SDK 仓库中 API 37 以 `android-37.0 / 37.1 / 37.2` 发布；
   本机与 CI 均安装 `android-37.0`。

---

## 8. 复现步骤

```bash
# 0) 环境
export JAVA_HOME=<JDK 21>
export ANDROID_HOME=<Android SDK 含 platforms;android-37.0 / build-tools;37.0.0 / NDK r29>

# 1) 单元测试（硬性门禁）
./gradlew :common:testDebugUnitTest

# 2) 锁状态检查（两者都必须为 0）
./gradlew :app:dependencies --configuration minimalDebugRuntimeClasspath \
  > diagnostics/dependencies-minimalDebug.txt
grep -c ' FAILED$' diagnostics/dependencies-minimalDebug.txt   # 期望 0
grep -ci leakcanary diagnostics/dependencies-minimalDebug.txt  # 期望 0

# 3) 编译 APK（权威解析）
./gradlew :app:assembleMinimalDebug

# 4) 静态检查
bash .github/scripts/apk-static-inspection.sh
```

若锁状态确实不一致，用官方方式收敛（**不要**手改或删除锁文件）：

```bash
./gradlew --write-locks --no-configuration-cache resolveAndLockAll
```

---

## 9. 本次交付改动规模

以 `pre-resolver-refactor` → `HEAD` 计：

```text
60 files changed, 7654 insertions(+), 262 deletions(-)
  33 新增 (A)   /   26 修改 (M)   /   1 删除 (D)
```

其中动态地址解析相关文件 30 个：

* **核心模块 18 个**（`common/.../data/endpoint/`）：
  `EndpointModel` `EndpointUrlFactory` `TxtRecordInterpreter` `SrvRecordInterpreter`
  `HttpsFallbackPolicy` `EndpointRefreshPolicy` `EndpointStateMachine` `EndpointResolver`
  `EndpointConnectGate` `DohDnsClient` `EndpointSettingsStore` `OkHttpEndpointProbe`
  `EndpointResolutionEngine` `EndpointRepository` `ServerUrlSynchronizer`
  `EndpointRepositoryImpl` `EndpointForegroundRefresher` `EndpointRefreshWorker`
* **DI 1 个**：`common/.../di/EndpointModule.kt`
* **设置界面 3 个**（`app/.../settings/endpoint/`）：
  `EndpointSettingsFragment` `EndpointSettingsView` `EndpointSettingsViewModel`
* **测试 9 个**（`common/src/test/.../data/endpoint/`）

---

## 10. 结论

| 验收项 | 结果 | 依据 |
|---|---|---|
| 单元测试全绿 | ✅ | 1070/1070，failures/errors/skipped 均为 0（§2） |
| 动态地址模块测试覆盖 | ✅ | 9 个类 / 164 个用例（§2.2） |
| Debug APK 可成功构建 | ✅ | `BUILD SUCCESSFUL in 4m 46s`，75,536,735 字节（§3） |
| APK 包名/标签/启动入口正确 | ✅ | §4.1 |
| 无未经解释的额外 `Leaks` 启动入口 | ✅ | 合并后清单无匹配；脚本硬性断言（§4.2） |
| 解析模块确实进入 APK | ✅ | DEX 中存在对应类型描述符（§4.1） |
| 依赖锁定报错已根治且未删锁文件 | ✅ | `--write-locks` 收敛，差异仅 LeakCanary（§5.4） |
| GitHub Actions 工作流已重写 | ✅ | §6 |
| **GitHub Actions 服务端已跑绿** | ⚠️ **未验证** | 本机无 `gh`/token，见 §7 |
| 未声称通过未实际执行的测试 | ✅ | §7 逐项列明未执行事项 |
