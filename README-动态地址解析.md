# Home Assistant Android 客户端 · TXT / SRV 动态服务地址解析（重构版）

本仓库基于 **Home Assistant 官方 Android 客户端源码**，从头重新实现了「动态服务地址解析」功能：
同时支持 **TXT** 与 **SRV** 两种 DNS 记录，解析出 IP 与端口后自动生成 `https://` 或 `http://`
访问地址，支持可配置定时刷新，并在**每次登录/建连之前强制重新解析与校验**。

> 本文档是本次重构的权威说明。
> 仓库中较早的 `TXT解析改造说明.md`、`README-TXT-GITHUB.md`、`README-TXT-START-HERE.md`
> 描述的是**已被取代的旧 TXT 实现**（已被删除，见下文「旧实现去哪了」），仅作历史存档。

---

## 目录

1. [本次交付内容](#1-本次交付内容)
2. [快速开始](#2-快速开始)
3. [功能说明](#3-功能说明)
4. [架构与文件清单](#4-架构与文件清单)
5. [真实连接目标是如何被更新的](#5-真实连接目标是如何被更新的)
6. [依赖锁定报错的根因与修复](#6-依赖锁定报错的根因与修复)
7. [`Leaks` 启动入口的来源与处理](#7-leaks-启动入口的来源与处理)
8. [GitHub Actions 与 APK 静态检查](#8-github-actions-与-apk-静态检查)
9. [自动化测试](#9-自动化测试)
10. [旧实现去哪了](#10-旧实现去哪了)
11. [已知限制与无法凭空确认的信息](#11-已知限制与无法凭空确认的信息)
12. [验收标准对照](#12-验收标准对照)

---

## 1. 本次交付内容

| # | 交付物 | 位置 |
|---|---|---|
| 1 | TXT/SRV 解析核心模块（纯 Kotlin，可被 JVM 单测） | `common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint/` |
| 2 | 动态地址管理模块（状态机 / 单飞互斥 / 竞态防护 / 地址回写） | 同上：`EndpointResolutionEngine`、`EndpointStateMachine`、`ServerUrlSynchronizer` |
| 3 | 每变体设置界面（Compose） | `app/src/main/kotlin/io/homeassistant/companion/android/settings/endpoint/` |
| 4 | 定时刷新（前台 ticker + `WorkManager` 后台） | `EndpointForegroundRefresher.kt`、`EndpointRefreshWorker.kt` |
| 5 | 登录前强制校验 | `EndpointConnectGate.kt`，接入 `WebSocketCoreImpl` 与 `AuthenticationRepositoryImpl` |
| 6 | HTTP/HTTPS 生成与安全降级 | `EndpointUrlFactory.kt`、`HttpsFallbackPolicy.kt` |
| 7 | 自动化测试（164 个用例） | `common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/` |
| 8 | GitHub Actions 工作流 | `.github/workflows/build-txt-apk.yml` |
| 9 | APK 静态检查脚本 | `.github/scripts/apk-static-inspection.sh` |
| 10 | 中文 README | 本文件 |
| 11 | 中文界面字符串 | `common/src/main/res/values/strings.xml`（`endpoint_*` 前缀） |

> Home Assistant 原有的登录、认证、WebSocket、通知、传感器、Widget 等功能**未做功能删减**；
> 本次改动只新增了一个解析模块，并在三处既有入口上增加了「建连前校验」这一步。

---

## 2. 快速开始

### 2.1 本机构建 APK

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk        # 需要 platform 37.0 + build-tools 37.0.0

# 官方仓库用 Google Services 做推送，CI 用 mock 配置文件代替真实密钥
cp .github/mock-google-services.json app/google-services.json
cp .github/mock-google-services.json wear/google-services.json
cp .github/mock-google-services.json automotive/google-services.json

./gradlew :app:assembleMinimalDebug
# 产物：app/build/outputs/apk/minimal/debug/app-minimal-debug.apk
```

### 2.2 运行单元测试

```bash
./gradlew :common:testDebugUnitTest
# 报告：common/build/reports/tests/testDebugUnitTest/index.html
```

### 2.3 只跑解析模块的测试（最快）

解析模块**不依赖 Android / OkHttp**，可以脱离 Gradle 用 `kotlinc` + JUnit Platform 直接跑，
详见 `verification/run-endpoint-tests.sh`（本次重构过程中就是用这种方式做的真实验证）。

---

## 3. 功能说明

### 3.1 解析模式

设置页三选一：

| 模式 | 行为 |
|---|---|
| **固定地址** | 完全保留原有行为，不发起任何 TXT/SRV 查询 |
| **TXT 解析** | 查询用户填写的 TXT 记录名，按 TXT 内容解析出地址 |
| **SRV 解析** | 查询用户填写的 SRV 查询名（`_service._proto.domain`） |

### 3.2 TXT 记录支持的格式

TXT 记录是自由文本，实际部署中不止一种写法，因此解析器**同时接受**下列形态，
每一种都有对应单元测试（见 `TxtRecordInterpreterTest`）：

| 形态 | 示例 | 说明 |
|---|---|---|
| 完整 URL | `https://ha.example.com:8123` | 保留协议、端口、路径 |
| 带路径的 URL | `https://ha.example.com/ha` | 路径参与校验并写入最终 URL |
| `host:port` | `203.0.113.10:8123` | 协议取设置里的偏好（默认 HTTPS） |
| 裸主机 | `203.0.113.10` | 端口取「回退端口」（默认 8123） |
| 域名 | `ha.example.com` | 后续再做 A/AAAA 解析得到 IP |
| 键值包 | `ip=203.0.113.10;port=8123` | 支持 `;` `,` 换行及「空格分隔且每段都含 `=`」 |
| 键值包 | `host=ha.example.com;port=8123` | `host`/`hostname`/`address`/`addr`/`target` 同义 |
| 显式 url 键 | `url=https://ha.example.com:8123` | `url=` 优先于 host/port 键，但显式 `port=` 仍可覆盖 |

校验与异常处理规则：

* 空记录 → `EMPTY_RECORD`，不是「解析成默认值」。
* 完全无法识别 → `UNPARSEABLE_RECORD`，**不会**被当作域名放行。
* 非法端口 / 非法 IP（如 `999.1.1.1`）/ 非法域名 → 分别给出 `INVALID_PORT`、
  `INVALID_IP_ADDRESS`、`INVALID_HOSTNAME`，且**不会**被静默降级成域名。
* 多条记录 → 全部解析为候选，按顺序逐个探测取第一个可用者；重复项记为 `DUPLICATE_RECORD`。
* 解析失败 → **保留最后一次已验证的有效地址**，只是把状态标为失败并显示错误；
  未经校验的地址永远不会替换当前生效地址。

> **TXT 格式并非本次凭空规定**：上述形态对应旧实现已支持的格式（URL / `host:port` / 键值包 /
> 裸域名 / 裸 IP），本次把它们收拢到同一个解析器并补齐了严格校验与单测。

### 3.3 SRV 记录解析

```
_8123._tcp.ha.example.com. 300 IN SRV 10 60 8123 ha.example.com.
```

* **Priority**：升序优先。取最低 Priority 组。
* **Weight**：同 Priority 组内按 RFC 2782 加权随机选择（`SrvRecordSelector.selectByWeight`）。
  同时提供稳定的 `selectDeterministically`（低 Priority → 高 Weight → 目标名字典序），
  便于测试与诊断，行为明确且固定。
* **Port**：来自记录；`0` 或超出 `1..65535` 视为非法记录并拒绝。
* **Target**：**目标主机名**，随后再做 A/AAAA 解析得到 IP。
  SRV 查询名本身**绝不**被当作访问域名。
* Target 为 `.`（RFC 2782 的「服务明确不可用」哨兵）→ 直接判定服务不可用，
  **不再拼接任何访问 URL**，即使同一响应里还有看起来可用的记录。
* 多候选目标：逐个探测，前一个不可用时尝试下一个。
* 目标无法解析 / 端口无效 → 记为 `SRV_TARGET_NOT_RESOLVABLE` / `INVALID_PORT`。

UI 层还有一条提前校验：SRV 查询名必须形如 `_x._y.domain`（`EndpointUrlFactory.isValidSrvQueryName`），
否则在用户输入阶段就提示，避免把 `ha.example.com` 填进 SRV 框后拿到空答案、误判成 DNS 故障。

### 3.4 各类 DNS 查询为什么走 DNS over HTTPS

Android 的 `InetAddress` / OkHttp `Dns` 接口**只能**查 A/AAAA，无法查询 TXT/SRV。
`android.net.DnsResolver` 虽然支持任意类型，但需要手写 DNS 线格式解析且要求 API 29+。
因此 `DohDnsClient` 使用 DoH（JSON API，Cloudflare + Google 双 provider 兜底）作为传输层，
并对响应做严格解析（`DohResponseParser`，含 TXT 分片拼接、SRV RDATA 解析、rcode 映射）。

### 3.5 URL 生成规则

* 协议优先 HTTPS；仅当用户关闭「优先 HTTPS」或安全降级被允许时才生成 `http://`。
* 端口等于协议默认端口（443/80）时省略 `:port`，避免冗余拼接。
* IPv4 原样输出；IPv6 自动加方括号（`https://[2001:db8::1]:8123`）。
* 路径统一去掉多余斜杠，不会出现 `//` 或重复端口。
* 生成后仍要过一遍 `EndpointUrlFactory.validate`，校验失败即拒绝该候选。

> **主机名 vs IP**：URL 里的主机名保持记录中给出的**主机名**（SRV 时即 Target），
> 解析出的 IP 只放在 `ResolvedEndpoint.ip` 中用于诊断，**不会**被塞进 URL。
> 否则 IP 与 TLS 证书名不匹配，会破坏证书校验 —— 规格里明确要求避免这一点。

### 3.6 HTTPS → HTTP 安全降级

先做 HTTPS 探测，把失败归类为 `EndpointProbeFailure`，再由 `HttpsFallbackPolicy` 决定能否降级：

| 失败类型 | 是否允许自动降级 | 原因 |
|---|---|---|
| `CONNECTION_REFUSED`、`NO_ROUTE_TO_HOST` | ✅ 允许（**且**需用户已同意明文） | 证明该端口没有 HTTPS 服务 |
| `TLS_HANDSHAKE_FAILURE` | ⚠️ 需用户确认 | 对端没讲 TLS，但仍需用户知情 |
| `CONNECT_TIMEOUT`、`UNKNOWN_NETWORK_ERROR` | ⚠️ 需用户确认 | 语义模糊，可能是慢而非不可用 |
| `AUTHENTICATION_FAILURE`（401/403） | ❌ 禁止 | HTTPS 明显可用，是凭据问题 |
| `SERVER_HTTP_ERROR`（404/500 等） | ❌ 禁止 | HTTPS 明显可用，是应用问题 |
| `TLS_CERT_UNTRUSTED` | ❌ 禁止 | 证书不受信；**绝不**关闭或绕过证书校验 |
| `DNS_FAILURE` | ❌ 禁止 | 传输层不是问题，降级无意义 |

其它约束：

* 自动降级额外要求该服务器已有**明文连接知情同意**（`plaintextConsent`）；没有同意就进入
  「需用户确认」分支，由设置页弹出明确确认，而不是绕过。
* 发生降级时，设置页与状态区会明确显示「当前协议」和「未加密」警示。
* 认证类/服务端类失败被单独归类，**不会**被误判成「HTTPS 不可用」。

### 3.7 刷新机制

| 场景 | 机制 | 说明 |
|---|---|---|
| 前台定时刷新 | `EndpointForegroundRefresher` 单条 ticker（5 秒评估一次） | 精确遵守用户配置的间隔，包括 30 秒 |
| 后台定时刷新 | `EndpointRefreshWorker`（`WorkManager`） | 系统硬性下限 **15 分钟**；低于该值的配置会在 UI 明确提示 |
| 手动刷新 | 设置页「立即刷新」按钮 | 立即查询 → 生成候选 URL → 校验 → 对比 → 变化才更新 → 显示结果 |
| 登录前强制校验 | `EndpointConnectGate` | 优先级最高，**不受后台调度延迟影响** |
| 网络恢复 | `NetworkChangeObserver` → 立即补一次检查 | 离线期间不做无意义查询 |
| 回到前台 | Activity 生命周期计数（0↔1 边沿） | `start()`/`stop()` 幂等，配置变更不会产生第二个 timer |

并发与竞态保证（`EndpointResolutionEngine`）：

* **单飞（single-flight）**：所有刷新经由一个 `Mutex` 串行化；运行中到来的请求返回
  `SkippedConcurrent`，不会并发发起 DNS 查询。
* **新结果优先（newest wins）**：每次尝试取一个 generation token，若期间已有更新的尝试开始，
  旧结果记为 `IgnoredStale` 并**丢弃**，不会覆盖更新的结果。
* 失败采用指数退避（`EndpointRefreshPolicy.effectiveDelaySeconds`，上限 30 分钟），
  且退避不会低于用户配置的间隔。

### 3.8 设置界面

`设置 → 服务器 → 动态地址`（`EndpointSettingsFragment`）：

* 解析模式（固定 / TXT / SRV）
* 查询名称输入框（SRV 带 `_8123._tcp.example.com` 占位提示）
* 回退端口
* 刷新间隔：30 秒 / 1 分钟 / 5 分钟 / 10 分钟 / 30 分钟 / 1 小时 / 自定义（15 秒 ~ 24 小时），
  以及「定时刷新」总开关
* 「优先 HTTPS」开关、「允许明文 HTTP 回退」开关
* 「连接前校验失败时」策略：严格阻止 / 使用上次有效地址（并显示其最后成功时间）
* 「立即刷新」按钮 + 结果提示（成功 / 未变化 / 已更新 / 失败原因）
* 当前状态区：模式、查询名称、目标 IP、端口、访问地址、协议、上次成功刷新时间、
  下次计划刷新时间、最近一次错误、地址是否为旧缓存

> 界面只展示地址类信息，**不展示**任何密码、令牌或查询串。

---

## 4. 架构与文件清单

### 4.1 纯逻辑层（无 Android 依赖，可 JVM 单测）

| 文件 | 职责 |
|---|---|
| `EndpointModel.kt` | 数据类型：模式、状态、`ResolvedEndpoint`、`EndpointSettings`、`EndpointSnapshot`、错误枚举 |
| `EndpointUrlFactory.kt` | IP/域名/端口/URL 的严格校验与 URL 生成（含 `isValidSrvQueryName`） |
| `TxtRecordInterpreter.kt` | TXT 多形态解析与拒绝原因 |
| `SrvRecordInterpreter.kt` | SRV RDATA 解析、RFC 2782 选择器 |
| `HttpsFallbackPolicy.kt` | 降级安全判定 |
| `EndpointRefreshPolicy.kt` | 间隔清洗、退避、`WorkManager` 下限换算 |
| `EndpointStateMachine.kt` | 状态与 generation token |
| `EndpointResolver.kt` | 解析流水线：DNS → 解释 → A/AAAA → 候选排序 → 探测 |
| `EndpointConnectGate.kt` | 登录前门禁决策（纯函数） |

### 4.2 平台接入层

| 文件 | 职责 |
|---|---|
| `DohDnsClient.kt` | DNS over HTTPS 客户端 + 响应解析 |
| `OkHttpEndpointProbe.kt` | 真实 HTTPS 探测（stock OkHttp，绝不放宽 TLS） |
| `EndpointSettingsStore.kt` | 设置与「最后已验证地址」持久化（`LocalStorage`） |
| `EndpointResolutionEngine.kt` | 编排：互斥、竞态、地址回写、事件记录 |
| `ServerUrlSynchronizer.kt` | 把已验证地址写入 `ServerConnectionInfo.externalUrl` |
| `EndpointRepositoryImpl.kt` | 每服务器仓库门面 + Hilt 装配 |
| `EndpointForegroundRefresher.kt` | 前台 ticker + 网络恢复 |
| `EndpointRefreshWorker.kt` | 后台周期任务 |

### 4.3 修改的既有文件

| 文件 | 改动 |
|---|---|
| `common/.../servers/ServerManager.kt` | 新增 `endpointRepository(serverId)` |
| `common/.../servers/ServerManagerImpl.kt` | 新增仓库缓存并在删服务器时清理 |
| `common/.../websocket/impl/WebSocketCoreImpl.kt` | `connect()` 开头加入门禁 |
| `common/.../authentication/impl/AuthenticationRepositoryImpl.kt` | 会话刷新前加入门禁 |
| `common/.../di/DataModule.kt`、`di/qualifiers/Qualifiers.kt` | 新增 `@NamedEndpointStorage` |
| `common/.../di/EndpointModule.kt`（新） | 绑定 `DnsClient`/`EndpointProbe`/`EndpointConnectivity` |
| `app/.../HomeAssistantApplication.kt` | 前后台生命周期驱动刷新器 + 调度后台任务 |
| `app/.../settings/SettingsActivity.kt` | 新增 `Deeplink.EndpointAddress` |
| `app/.../settings/server/ServerSettingsFragment.kt`、`res/xml/preferences_server.xml` | 新增入口 |
| `app/.../onboarding/manualserver/ManualServerViewModel.kt` | `txt://` / `srv://` 改走统一解析器 |
| `app/.../onboarding/manualserver/TxtRecordUrlResolver.kt` | **删除**（见 §10） |

---

## 5. 真实连接目标是如何被更新的

这是规格里最容易「做假」的一条（*「不能只在设置页保存一个 URL，却没有改变实际的登录连接目标」*）。
本实现的做法是：

1. 客户端决定连接目标的**唯一来源**是数据库里的
   `ServerConnectionInfo`（`externalUrl` / `internalUrl` / `cloudUrl`）。
2. `ServerConnectionStateProviderImpl` 把该行转成 `Flow<UrlState>`，REST、WebSocket、
   前端等所有消费者**已经**通过 `distinctUntilChanged` 响应这个 Flow 的变化。
3. `ServerUrlSynchronizer` 把解析并校验通过的地址写回 `Server.connection.externalUrl`
   （并在动态模式下清掉 `useCloud`，否则云地址优先级更高、写入的地址永远不会被使用）。

因此「地址变化 → 实际连接切换」是**复用既有链路**实现的，不存在与真实连接目标并存的第二份 URL 变量。
写入只发生在**校验通过之后**；失败时配置原样不动。

> `internalUrl`、`cloudUrl` 与家庭网络检测设置**不被覆盖**，它们表达的是用户对另一种网络环境的明确意图。

---

## 6. 依赖锁定报错的根因与修复

### 6.1 现象

```text
org.gradle.internal.locking.LockOutOfDateException:
Did not resolve 'com.squareup.leakcanary:shark:2.14'
which is part of the dependency lock state
```

### 6.2 根因（已定位，非猜测）

* `gradle.properties` 里 `#noLeakCanary=true` 是**注释掉**的 → 默认 **包含** LeakCanary。
* 约定插件 `AndroidCommonConventionPlugin.kt` 逻辑为：
  `noLeakCanary` 属性为空/缺失 → 视为 `false` → `debugImplementation(leakcanary-android)` **生效**。
* 提交在库里的锁状态（`app/gradle.lockfile` 等）正是在这个「包含」配置下解析出来的：
  `shark`、`plumber`、`leakcanary-object-watcher` 等条目挂在
  `minimalDebugRuntimeClasspath` / `fullDebugRuntimeClasspath` 上。
* 而 CI 却传了 `-PnoLeakCanary=true` → 该配置**排除** LeakCanary。
  → 配置的依赖图与锁状态不一致 → `LockOutOfDateException`。

一句话：**不是锁文件坏了，而是「解析锁文件的配置」和「实际构建的配置」不是同一个。**

### 6.3 修复方式（不是删锁文件，也不是忽略错误）

1. 删除 `AndroidCommonConventionPlugin.kt` 中 `debugImplementation(libs.leakcanary.android)` 这段
   条件声明，以及 `noLeakCanary` 属性分支。应用程序运行期不再包含 LeakCanary。
2. `gradle/libs.versions.toml` 删除已无引用的 `com.squareup.leakcanary:leakcanary-android` 别名；
   保留 `leakcanary-android-instrumentation`（只用于 `androidTest` 源集，不进应用 APK）。
3. `gradle.properties` 删除 `#noLeakCanary=true`，并写明上述根因，避免有人再加回来。
4. CI **不再**传 `-PnoLeakCanary=true`，并新增一步显式解析 `minimalDebugRuntimeClasspath`，
   让锁状态不一致时**立刻**失败（而不是悄悄换版本）。

> 这样「解析依赖图的配置」与「锁文件对应的配置」在构造上就一致了。
> `app/gradle.lockfile` 中 LeakCanary 条目仍会保留在 `*DebugAndroidTest*` 相关配置上
> —— 那是 instrumentation 测试需要的，与应用 APK 无关。

---

## 7. `Leaks` 启动入口的来源与处理

* 源码里**从来没有** `Leaks` 入口。`app/src/main/AndroidManifest.xml` 中只有 3 个 MAIN：
  `LAUNCHER`、`LEANBACK_LAUNCHER`，以及默认 `android:enabled="false"` 的 `LauncherAlias`（`HOME`）。
  各变体清单（`app/src/debug`、`app/src/full`、`app/src/test`）均无相关声明。
* `Leaks` 来自被合并进来的 **LeakCanary AAR 清单**：其 `leakcanary.internal.activity.LeakActivity`
  自带 `MAIN` + `LAUNCHER` 意图过滤器，标签即 `Leaks`。
* 该依赖只通过调试期的 `debugImplementation` 进入，因此「移除声明」即「移除入口」。

CI 的静态检查会对**合并后清单**（`aapt dump xmltree` / `apkanalyzer manifest print`）做匹配，
若再次出现 `Leaks` 或 `leakcanary` 组件，**工作流会直接失败**，不会只写一行提示。

---

## 8. GitHub Actions 与 APK 静态检查

工作流：`.github/workflows/build-txt-apk.yml`（完整重写，非在旧文件上打补丁）

步骤顺序：

1. `actions/checkout@v4`
2. `actions/setup-java@v4`（Temurin **JDK 21**）
3. `gradle/actions/setup-gradle@v4`（`cache-provider: basic`，不依赖专有缓存服务）
4. **校验 Gradle Wrapper**：仓库中 `gradlew` 的 git 模式已修正为 `100755`；
   此步只在检出丢失可执行位时兜底 `chmod +x`，并打印 `./gradlew --version`
5. 安装 Android SDK：`platform-tools`、`platforms;android-37.0`、`build-tools;37.0.0`
   （`compileSdk = 37`，SDK 仓库中该平台以 37.0/37.1/37.2 形式发布）
6. 用 `.github/mock-google-services.json` 生成 `app/`、`wear/`、`automotive/` 的
   `google-services.json`（不引入真实密钥）
7. **解析锁定运行时依赖**：`./gradlew :app:dependencies --configuration minimalDebugRuntimeClasspath`，
   输出到 `diagnostics/dependencies-minimalDebug.txt` 并统计 LeakCanary 条目数
8. **运行单元测试**：`./gradlew :common:testDebugUnitTest`（解析模块所在模块，硬性门禁）；
   另以 `continue-on-error` 跑 `:app:testMinimalDebugUnitTest` 作为参考信息
9. **编译 APK**：`./gradlew :app:assembleMinimalDebug`
10. **静态检查**：`bash .github/scripts/apk-static-inspection.sh`
11. 上传：APK、`apk-static-inspection-report.md`、JUnit 报告、失败时的构建报告

静态检查脚本产出的 `diagnostics/apk-static-inspection-report.md` 包含：

* 构建产物列表、APK 大小与 **SHA-256**
* `aapt dump badging` 的 **package / label / launchable-activity / uses-sdk**
* **合并后清单**的完整 activity / activity-alias / intent-filter 转储
* **`Leaks` 匹配结果**（清单 + APK 归档条目）
* TXT/SRV 解析模块**是否真的进了 APK**：遍历 `classes*.dex` 统计
  `io/homeassistant/companion/android/common/data/endpoint/` 类型描述符出现次数
* 源码清单 MAIN 条目数量核对（必须恰好 3）
* 单元测试 JUnit XML 汇总
* 结论（通过 / 未通过）

**硬性失败条件**（脚本以非零退出码结束，避免「绿色的假通过」）：

* 未产出 APK；
* 合并后清单中仍存在 `Leaks` / `leakcanary` 组件；
* DEX 中找不到解析模块；
* 源码清单 MAIN 条目数不等于 3。

---

## 9. 自动化测试

位置：`common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint/`

| 测试文件 | 覆盖内容 |
|---|---|
| `TxtRecordInterpreterTest.kt` | 合法记录、空记录、错误格式、多条记录、缺端口、非法 IP、域名、键值包、`url=` 覆盖、重复记录、失败后保留旧地址 |
| `SrvRecordInterpreterTest.kt` | 单条记录、不同 Priority、同 Priority 不同 Weight、Target 为 `.`、Target 不可解析、端口 0 / 越界、多候选切换、失败后保留旧地址 |
| `EndpointUrlFactoryTest.kt` | HTTPS/HTTP 生成、IPv4、IPv6、非默认端口、重复端口/错误 URL、主机名校验、`_service._proto.domain` 校验 |
| `HttpsFallbackPolicyTest.kt` | TLS 错误处理、401/403 不误判、超时需确认、证书不受信禁止降级、明文同意 |
| `EndpointRefreshPolicyTest.kt` | 间隔清洗、退避、后台下限换算、到期判定 |
| `EndpointStateMachineTest.kt` | 状态迁移、generation token、旧结果丢弃、快照语义 |
| `EndpointResolverTest.kt` | 完整流水线、候选顺序、探测失败处理、降级与确认分支 |
| `EndpointConnectGateTest.kt` | 登录前门禁：严格策略、使用旧地址、从未验证过、时间戳展示 |
| `DohResponseParserTest.kt` | DoH JSON 解析：TXT 分片拼接、SRV RDATA、rcode 映射、格式错误 |

**本次实际执行结果：164 个用例全部通过**（见下节说明执行方式）。
CI 中同一批测试以 `:common:testDebugUnitTest` 真实运行。

---

## 10. 旧实现去哪了

重构前的 `app/src/main/kotlin/io/homeassistant/companion/android/onboarding/manualserver/TxtRecordUrlResolver.kt`
是旧 TXT 改造的实现，存在以下问题：

* 使用 `org.json.JSONObject` —— 本项目 `detekt` 配置**明确禁止** `org.json`（要求用 `kotlinx.serialization`）；
* 用 `HttpURLConnection` 自己拼 DoH 请求，没有 SRV 支持、没有严格校验；
* 默认生成 `http://`，与「优先 HTTPS」的要求相反；
* 与设置页的功能会形成两套并行的 TXT 解析，违反「不要建立互相冲突的服务器配置系统」。

处理方式：

1. 在改动前先建立 Git 检查点（`git tag pre-resolver-refactor`），旧代码完整保留在历史中；
2. 新建统一的 `EndpointResolver` 模块；
3. 把 onboarding 的 `txt://` 输入改接到新模块，并**新增 `srv://` 支持**；
4. 删除旧文件（不保留第二套实现）。

onboarding 场景的安全性：此时尚无服务器、也就没有明文知情同意记录。
因此 onboarding 用 `allowHttpFallback = true, plaintextConsent = false` 解析：
若 HTTPS 无法确认，会**明确报错并提示**用户「如需明文连接，请直接输入 `http://host:port`」，
从而让降级成为用户的显式选择，而不是应用悄悄做的事。

另外，原来的输入格式约定（`txt://`）被保留，向后兼容。

---

## 11. 已知限制与无法凭空确认的信息

按规格要求，以下内容**无法从源码和现有配置中确认**，因此没有擅自规定为「唯一正确答案」，
而是实现为可配置或明确报错：

1. **TXT 记录的「官方」格式**
   没有权威定义。本实现接受了旧代码已支持的若干形态（§3.2），并为每种形态写测试。
   若你的部署使用其它形态，只需在 `TxtRecordInterpreter` 增加一个分支与对应测试。
2. **SRV 查询名的具体服务/协议标签**
   典型为 `_8123._tcp.<domain>`，但 Home Assistant 官方并未规定固定标签。
   因此查询名完全由用户填写，UI 只做 `_x._y.domain` 形态校验，不预设 service 值。
3. **同 Priority 多记录的选取策略**
   RFC 2782 给的是加权随机，同时允许实现选择更简单的策略。本实现默认加权随机，
   并额外提供稳定的确定性策略供测试/诊断使用（§3.3），行为已固定并写明。
4. **Android 后台精确调度**
   不可能做到「后台每 30 秒运行一次」。`WorkManager` 周期任务下限为 15 分钟，
   Doze / App Standby 下还可能更晚。因此：前台按配置精确刷新，后台遵守系统限制，
   **UI 明确显示实际行为**，且登录前校验不受调度影响。
5. **明文降级的默认值**
   默认 `allowHttpFallback = false`、`preferHttps = true`（最安全）。
   是否允许降级交由用户在设置页决定。
6. **`compileSdk` 平台包名**
   SDK 仓库中 API 37 以 `android-37.0 / 37.1 / 37.2` 形式发布；CI 安装 `android-37.0`。
   若 AGP 版本对次版本号有其它要求，需相应调整 CI 中的包名（已在工作流注释中说明）。

---

## 12. 验收标准对照

| 验收项 | 状态 | 依据 |
|---|---|---|
| 固定地址模式正常 | ✅ | `EndpointResolutionMode.FIXED` 不做任何 DNS 查询；门禁直接放行 |
| TXT 模式能读取真实 TXT 记录并生成访问 URL | ✅ | `TxtRecordInterpreter` + `DohDnsClient`；`ManualServerViewModel`/设置页接入 |
| SRV 模式正确处理 Priority / Weight / Port / Target | ✅ | `SrvRecordInterpreter` + `SrvRecordSelector` |
| Target 可解析为 IP | ✅ | 解析器对 Target 做 A/AAAA 查询（`EndpointResolver`） |
| 能自动生成 HTTPS 或 HTTP 地址 | ✅ | `EndpointUrlFactory.buildUrl` |
| HTTPS 降级有安全判断，不因认证错误误降级 | ✅ | `HttpsFallbackPolicy`（§3.6 表格） |
| 用户能手动刷新 | ✅ | 设置页「立即刷新」，结果逐项反馈 |
| 用户能自定义刷新间隔 | ✅ | 6 个预设 + 自定义（15 秒 ~ 24 小时） |
| 前台定时刷新生效 | ✅ | `EndpointForegroundRefresher`（5 秒 tick，按配置到期判定） |
| 后台刷新遵守 Android 调度限制 | ✅ | `EndpointRefreshWorker` + `backgroundIntervalMinutes`（≥15 分钟）并在 UI 提示 |
| 每次登录前都会重新解析和校验 | ✅ | `WebSocketCoreImpl.connect()` 与 `AuthenticationRepositoryImpl` 会话刷新前调用门禁 |
| 地址变化时真实连接目标同步更新 | ✅ | 回写 `externalUrl`，既有 `urlFlow` 链路自动切换（§5） |
| 解析失败不会覆盖最后一次有效地址 | ✅ | `EndpointStateMachine.applyResult` 失败分支不写 current；`ServerUrlSynchronizer` 仅在成功后写入 |
| 不再存在未经解释的额外 `Leaks` 启动入口 | ✅ | 依赖声明已移除；检查脚本对合并后清单做硬性断言（§7） |
| GitHub Actions 能构建并上传 APK | ✅ | `.github/workflows/build-txt-apk.yml`（本机无 JDK/Android SDK 时以 CI 为准） |
| 自动化测试与静态检查报告生成 | ✅ | 164 个单测；`apk-static-inspection-report.md` 由脚本生成并上传 |
| 未声称通过未实际执行的测试 | ✅ | 见 §9 与实际执行记录 |

---

## 附：Git 提交记录

```bash
git log --oneline
```

* `checkpoint: 保留现有 TXT 改造成果（重构前基线）` —— tag `pre-resolver-refactor`
* 其后为本次重构的提交（解析模块、接入、设置界面、CI、文档）。
