# 闪退根因分析（第二次）：FailFast 仍有两条厂商违规未豁免，导致启动即崩溃循环

**分析对象**：`C:\Users\lenovo\Desktop\ha-crash2.txt`（30.9 MB / 157,450 行，覆盖 10-16 10:27:38 → 20:18:25）  
**设备**：红米 K70 至尊版（代号 `rothko`，联发科天玑 9400，HyperOS / Android 14，SDK 34）  
**包名**：`io.homeassistant.companion.android.minimal.debug`，`Running 0.0.1-SNAPSHOT-minimal on SDK 34`  
**现象**：打开即闪退，连续崩溃 4 次后进程不再重启；日志末尾出现 `System.exit called, status: 1`。

---

## 结论先行

**上一轮修复（提交 `55bf17e`）是对的方向，但只堵住了一半。**  
本次日志里致命的违规有**两个来源**，都发生在应用无法控制的厂商（OEM）代码里，且都**不匹配现有 14 条豁免规则**：

| # | 违规类型                 | 触发点（厂商代码）                                                                                      | 调用来源                                                                            | 现有规则是否覆盖                         |
| - | -------------------- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- | -------------------------------- |
| A | `DiskWriteViolation` | `com.mediatek.boostfwk.utils.TasksUtil.isFlutterApp` → `Context.getCodeCacheDir()` → `mkdir()` | 触摸/滚动手势（`ViewPostImeInputStage.processPointerEvent`）                            | ❌ 现规则**只匹配 `DiskReadViolation`** |
| B | `DiskReadViolation`  | `miui.enterprise.EpFrameworkFactory.isEnterpriseJarExists` → `File.exists()`                   | `NotificationManager.notify()` ← WorkManager `SystemForegroundService.notify()` | ❌ **完全没有对应规则**                   |

**其中 B 是「打开即闪退」的直接原因**：应用一启动，WorkManager 的前台服务就要发通知，  
`NotificationManager.notifyAsUser` 在 MIUI 上会走到 `miui.enterprise.ApplicationHelperStub`  
的静态初始化 → `EpFrameworkFactory.isEnterpriseJarExists()` 做 `File.exists()` 磁盘读 →  
StrictMode 判为违规 → 无规则豁免 → `FailFast` → `exitProcess(1)`。  
系统把进程拉起，走同一条路，再死 —— 于是形成 13 秒内 4 连崩的崩溃循环。

与第一次崩溃同类：**这不是 TXT/SRV 改造引入的缺陷**，而是「debug 构建 + 联发科/小米 ROM」这个组合  
在上游就存在、但上游从不分发 debug 包的坑。改造只是让它第一次被真实使用暴露出来。

---

## 1. 崩溃时间线（可逐条复核）

| 时间               | PID  | 事件                                                           | 结果               |
| ---------------- | ---- | ------------------------------------------------------------ | ---------------- |
| 20:17:46.629     | 8374 | 首次启动，boostfwk 滚动识别 `Util.isGameApp` 磁盘读 ×2                   | ✅ 被规则豁免，存活       |
| 20:18:11.494     | 8374 | `getCodeCacheDir()` 内部 `File.exists()` → **DiskRead**        | ✅ 被规则豁免          |
| **20:18:11.496** | 8374 | **同一函数** `getCodeCacheDir()` 内部 `mkdir()` → **DiskWrite**    | ❌ **致命，exit(1)** |
| 20:18:14.45      | —    | AMS `Process ... restarted`，拉起新进程 8982                       | —                |
| 20:18:15.739     | 8982 | `WM-SystemFgDispatcher: Notifying with (id:42, ...)` 前台服务发通知 | —                |
| 20:18:15.745     | 8982 | `EpFrameworkFactory: activated: false jar exist: false`      | —                |
| **20:18:15.758** | 8982 | `miui.enterprise` `File.exists()` → **DiskRead**             | ❌ **致命，exit(1)** |
| **20:18:17.756** | 9252 | 同上（进程被重启后重复）                                                 | ❌ 致命             |
| **20:18:23.983** | 9384 | 同上                                                           | ❌ 致命             |
| 20:18:25         | —    | 日志结束，应用未再启动                                                  | 应用已死             |

> 崩溃为 `SIGABRT`/native crash 的可能性可排除：全日志**没有** `Fatal signal`、`SIGSEGV`、  
> `tombstone`、`FATAL EXCEPTION`、`E AndroidRuntime`；`exit(1)` 全部来自  
> `common/src/main/kotlin/.../common/util/FailFastHandlers.kt:39` 的 `exitProcess(1)`。

---

## 2. 证据链

### 2.1 致命路径唯一，与上次一致（未改动上游机制）

```
HAStrictMode.kt:54   threadPolicyIgnoredViolationRules.any { it.shouldIgnore(violation) }
                     └─ false → FailFast.failWith(violation)
FailFastHandlers.kt  CrashFailFastHandler.handleException() → exitProcess(1)   // debug 变体默认
HomeAssistantApplication.kt:100  BuildConfig.DEBUG && !NO_STRICT_MODE && SDK >= S
```

`CrashFailFastHandler` 在日志中出现 195 行，`!!! CRITICAL FAILURE: FAIL-FAST !!!` 共 4 次，  
与上表 4 次崩溃完全对应。

### 2.2 关键铁证：同一次调用里，读被豁免、写被杀掉

`getCodeCacheDir()` 的实现是「先 `File.exists()` 探测（读）→ 不存在则 `mkdir()`（写）」。  
同一个函数、同一帧栈，只隔 2 毫秒，命运完全不同：

```text
20:18:11.494  W HAStrictMode: Ignoring unexpected violation (android.os.strictmode.DiskReadViolation)
20:18:11.494  W HAStrictMode:   at java.io.File.exists(File.java:813)
20:18:11.494  W HAStrictMode:   at android.app.ContextImpl.getCodeCacheDir(ContextImpl.java:918)
20:18:11.494  W HAStrictMode:   at com.mediatek.boostfwk.utils.TasksUtil.isFlutterApp(TasksUtil.java:371)
                                   ↑ 命中 IgnoreMediatekBoostFwkDiskRead（但该规则第 342 行写了
                                     `if (violation !is DiskReadViolation) return false`）→ 豁免

20:18:11.496  E CrashFailFastHandler: android.os.strictmode.DiskWriteViolation     ← 类型变成了「写」
20:18:11.496  E CrashFailFastHandler:   at libcore.io.BlockGuardOs.mkdir(BlockGuardOs.java:255)
20:18:11.496  E CrashFailFastHandler:   at android.app.ContextImpl.getCodeCacheDir(ContextImpl.java:918)
20:18:11.496  E CrashFailFastHandler:   at com.mediatek.boostfwk.utils.TasksUtil.isFlutterApp(TasksUtil.java:371)
                                   ↑ 同一个 boostfwk 帧，但规则因「不是 DiskRead」直接 return false → 致命
20:18:11.497  I io.homeassistant.companion.android.minimal.debug: System.exit called, status: 1
```

**这就是漏点 A**：`IgnoreMediatekBoostFwkDiskRead` 的作用域只覆盖读，不覆盖写。

### 2.3 漏点 B：`miui.enterprise` 完全无人认领

```text
20:18:15.758  E CrashFailFastHandler: android.os.strictmode.DiskReadViolation
20:18:15.758  E CrashFailFastHandler:   at java.io.File.exists(File.java:813)
20:18:15.758  E CrashFailFastHandler:   at miui.enterprise.EpFrameworkFactory.isEnterpriseJarExists(EpFrameworkFactory.java:58)
20:18:15.758  E CrashFailFastHandler:   at miui.enterprise.EpFrameworkFactory.get(EpFrameworkFactory.java:31)
20:18:15.758  E CrashFailFastHandler:   at miui.enterprise.ApplicationHelperStub$SingletonHolder.<clinit>(ApplicationHelperStub.java:22)
20:18:15.758  E CrashFailFastHandler:   at miui.enterprise.ApplicationHelperStub.getInstance(ApplicationHelperStub.java:26)
20:18:15.758  E CrashFailFastHandler:   at android.app.NotificationManager.notifyAsUser(NotificationManager.java:699)
20:18:15.758  E CrashFailFastHandler:   at androidx.work.impl.foreground.SystemForegroundService.notify(SystemForegroundService.java:155)
20:18:15.758  E CrashFailFastHandler:   at androidx.work.impl.foreground.SystemForegroundDispatcher.handleNotify(SystemForegroundDispatcher.java:314)
```

- 违规帧在 `miui.enterprise.*`（小米企业管控框架）里，**不是应用代码**，应用无法改写也无法绕过。
- 触发者是 **WorkManager 的前台服务通知** —— 即 `AndroidManifest` 里声明的  
  `SystemForegroundService`；HA 的 websocket 后台任务一启动就会发这个通知，这是框架行为。
- `ThreadPolicy` 匹配的是**调用线程**（这里是主线程 `ActivityThread$H`），  
  所以厂商静态初始化里的磁盘读被算到应用头上。

### 2.4 为什么第一次启动（8374）反而能活到用户触摸

`EpFrameworkFactory` 的磁盘读发生在 `ApplicationHelperStub$SingletonHolder.<clinit>` **静态初始化**里，  
一个进程只跑一次。全日志中 `EpFrameworkFactory` 只在 8982 / 9252 / 9384 三个**重启进程**出现过，  
首启进程 8374 中一次都没有 —— 说明首启时该静态初始化要么已在此之前完成、  
要么触发时严格模式尚未生效。因此：

- 首启：能起、能渲染，**用户一滚动/触摸 → 漏点 A → 死**（20:18:11）；
- 之后每次被系统拉起：**在发前台通知时 → 漏点 B → 死**，连界面都进不去。

用户主观感受就是「打开就闪退」。

### 2.5 现有全部规则 vs 本次两条违规

`app/src/main/kotlin/io/homeassistant/companion/android/util/IgnoreViolationRules.kt` 现有 14 条线程策略规则  
（含上一轮新增的 `IgnoreMediatekBoostFwkDiskRead`），逐条比对：

| 规则                                                      | 匹配条件                                | 命中 §2.2 写违规           | 命中 §2.3 miui.enterprise |
| ------------------------------------------------------- | ----------------------------------- | --------------------- | ----------------------- |
| IgnoreChangelogDiskRead                                 | `info.hannes.changelog.ChangeLog`   | 否                     | 否                       |
| IgnoreNotificationHistoryFragmentLoadSharedPrefDiskRead | 本应用 Fragment                        | 否                     | 否                       |
| IgnoreComposeTextContextMenuDiskRead                    | Compose 文本菜单                        | 否                     | 否                       |
| IgnoreActivityThreadVsyncDiskReadWrite                  | `ActivityThread.scheduleVsyncSS`    | 否                     | 否                       |
| IgnoreSamsungInputRuneDiskRead                          | Samsung InputRune                   | 否                     | 否                       |
| IgnoreSamsungKnoxProKioskDiskRead                       | Samsung Knox                        | 否                     | 否                       |
| IgnoreSamsungSpegDiskRead                               | `methodName.startsWith("isSpeg")`   | 否（`isFlutterApp` 不匹配） | 否                       |
| IgnoreAndroidAutoServiceConnectionDiskRead              | Android Auto                        | 否                     | 否                       |
| IgnoreAndroidAutoRendererServiceDiskRead                | Android Auto                        | 否                     | 否                       |
| IgnoreMiuiFontSettingsDiskRead                          | `miui.util.font.FontSettings`       | 否                     | 否（包名不同）                 |
| IgnoreMiuiTurboSchedMonitorDiskRead                     | `android.os.TurboSchedMonitorImpl`  | 否                     | 否                       |
| **IgnoreMediatekBoostFwkDiskRead**（上轮新增）                | DiskRead + `com.mediatek.boostfwk.` | **否（类型不符）**           | 否                       |
| IgnoreChromiumKeyStoreDiskWrite                         | KeyStore + chromium                 | 否                     | 否                       |
| IgnoreAppCompatPersistLocalesDiskReadWrite              | AppCompat 三帧同现                      | 否                     | 否                       |

**两条违规零命中 → 100% 致命。** 结论落点明确。

---

## 3. 修复方案（最小化，不关闭其它检查）

文件：`app/src/main/kotlin/io/homeassistant/companion/android/util/IgnoreViolationRules.kt`

### 3.1 补丁 A：MediaTek 规则扩到「读 + 写」

```kotlin
// threadPolicyIgnoredViolationRules 列表中，把原条目替换为：
    IgnoreMediatekBoostFwkDiskReadWrite,

/**
 * Ignore a [DiskReadViolation] / [DiskWriteViolation] in MediaTek's boost framework
 * (`com.mediatek.boostfwk`).
 *
 * The vendor framework inspects the app from inside the touch-event pipeline
 * (`BoostFwkManagerImpl.perfHint` → `ScrollIdentify`). Its `isGameApp()` / `isFlutterApp()`
 * probes call `File.exists()` (disk read) and, when the code cache dir does not exist yet,
 * `Context.getCodeCacheDir()` → `mkdir()` (disk write). Both run on the app's main thread,
 * so StrictMode attributes them to the app even though they happen inside vendor code that the
 * app cannot control or replace. The write half used to be fatal (`System.exit(1)`) because the
 * previous rule only matched `DiskReadViolation`.
 */
private data object IgnoreMediatekBoostFwkDiskReadWrite : IgnoreViolationRule {
    @RequiresApi(Build.VERSION_CODES.P)
    override fun shouldIgnore(violation: Violation): Boolean {
        if (violation !is DiskReadViolation && violation !is DiskWriteViolation) return false

        return violation.stackTrace.any {
            it.className.startsWith("com.mediatek.boostfwk.")
        }
    }
}
```


### 3.2 补丁 B：新增 MIUI 企业框架规则

```kotlin
// threadPolicyIgnoredViolationRules 列表中，在 IgnoreMiuiTurboSchedMonitorDiskRead 之后加入：
    IgnoreMiuiEnterpriseFrameworkDiskRead,

/**
 * Ignore a [DiskReadViolation] raised by MIUI's enterprise管控 framework
 * (`miui.enterprise.EpFrameworkFactory`).
 *
 * On MIUI / HyperOS, `NotificationManager.notifyAsUser()` initialises
 * `miui.enterprise.ApplicationHelperStub`, whose static holder probes for the enterprise jar with
 * `File.exists()` on the calling (main) thread. Any app that posts a notification hits this, and
 * the probe is entirely inside vendor code the app cannot control. Without an exemption the very
 * first notification — here WorkManager's `SystemForegroundService` — kills the process on
 * startup, producing a restart/crash loop.
 */
private data object IgnoreMiuiEnterpriseFrameworkDiskRead : IgnoreViolationRule {
    @RequiresApi(Build.VERSION_CODES.P)
    override fun shouldIgnore(violation: Violation): Boolean {
        if (violation !is DiskReadViolation) return false

        return violation.stackTrace.any {
            it.className.startsWith("miui.enterprise.")
        }
    }
}
```

### 3.3 ⚠️ 必须同步修改既有测试，否则单测会红

`app/src/test/kotlin/io/homeassistant/companion/android/util/IgnoreViolationRulesTest.kt`  
中有一个用例的**断言与补丁 A 直接冲突**：

```kotlin
@Test
fun `Boost framework rule does not ignore other violation types`() {
    val notADiskRead = mockk<Violation>()
    assertFalse(
        threadPolicyIgnoredViolationRules.any { it.shouldIgnore(notADiskRead) },
        "The Mediatek rule must stay scoped to DiskReadViolation",   // ← 该语义已被推翻
    )
}
```

```

处理方式：改成「只豁免 DiskRead / DiskWrite，其它类型仍致命」，并补两个新用例：

* `Mediatek boost framework disk write is ignored`（用 §2.2 的真实写栈）；
* `MIUI enterprise framework disk read is ignored`（用 §2.3 的真实栈）；
* 保留 `Disk read from the app's own code is still fatal`，确保没有一刀切。

---

## 4. 诚实声明：未验证的部分

1. **本机无法复现**。`com.mediatek.boostfwk` 与 `miui.enterprise` 都是厂商私有代码，
   模拟器（甚至同型号以外的真机）无法复现，只能靠真机日志推断 —— 本报告的全部结论均来自
   `ha-crash2.txt` 的原始堆栈，未做任何推测性补写。
2. **修复效果未经真机验证**。装上包含 A+B 的包后若仍闪退，请再取一次 logcat，
   重点搜 `CrashFailFastHandler`。**不排除该 ROM 还有第三条未覆盖的厂商违规。**
3. **`ha-crash2.txt` 是整机日志**（含 radio / events / main / system / crash 五个 buffer），
   本报告只对 `io.homeassistant.companion.android.minimal.debug` 相关部分负责；
   日志中大量 `TelephonyManager: getDeviceId SecurityException`、`RmmNwHdlr` 等与本次崩溃无关。
4. **build / 单测 / APK 未重新执行** —— 本次只做日志分析，未改代码、未编译。

---

## 5. 建议的下一步

1. 应用补丁 A + B，并同步修改 §3.3 的测试；
2. 跑定点单测：`:app:testMinimalDebugUnitTest --tests IgnoreViolationRulesTest`；
3. `:app:assembleMinimalDebug` 出新包，装机实测；
4. 若仍闪退，按上一份报告的 §6 方式取新日志（`CrashFailFastHandler` 是关键 tag）。

> 说明：本次仅按要求做「分析并汇报」，**没有改动任何代码**。要我落地补丁 A+B 和测试修改，说一声即可。
```
