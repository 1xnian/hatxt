# TXT 记录查询故障：定位、修复与验证

本文只记录**这一轮**的工作：`txt://hat.22345678.xyz` 提示"无法解析记录 / TXT 查询失败"的**真实根因**，
以及为修复它所做的最小范围改动、构建结果与测试结果。

没有重新创建项目，没有改动原版 APP 的任何既有功能，Git 历史保留。

---

## 0. 一句话结论

故障**发生在 DNS 查询阶段**，不在 TXT 响应解析、不在字符串格式校验、也不在 URL 转换阶段。

原因有两层，缺一不可：

1. **查询通道全部不可达。** 原实现只配置了两个 DoH（DNS over HTTPS）provider：Cloudflare 与 Google。
   在本机（与用户同一网络环境）**实测两个都连不上**，而 Windows 的 `nslookup` 用的是系统 UDP DNS，
   所以"nslookup 能成功、APP 失败"完全成立。原来的实现里**没有任何 UDP 通道**。
2. **一段记录即使查到了也用不了。** TXT 值为 `http://117.183.5.29:13481` 或 `tcp://117.183.5.29:13481` 时，
   目标端口实测是**纯 HTTP 服务**（返回 200，不是 TLS 服务），而旧策略无法证明"HTTPS 不可用"，
   于是永远不降级，用户拿不到可用地址。

另外，旧实现把所有 DNS 异常折叠成一条 `DNS_LOOKUP_FAILED`，无法区分"名字不存在""没有该类型的记录"
"解析器连不上""响应畸形"，这是用户明确要求修掉的部分。

---

## 1. 根因定位（全部为实测证据，无推测）

### 1.1 系统 DNS 能查到记录

```
$ nslookup -type=TXT hat.22345678.xyz 223.5.5.5
hat.22345678.xyz   text = "117.183.5.29:13481"     权威服务器 public1.alidns.com
```

自建 UDP 客户端（本仓库 `verification` 下的临时脚本，非项目代码）实测原始报文：

```
=== hat.22345678.xyz  type=16  server=223.5.5.5
   txid_matches=True rcode=0 ancount=1 bytes=65
   hat.22345678.xyz type=16 ttl=600 strings=['117.183.5.29:13481'] joined='117.183.5.29:13481'
=== hat.22345678.xyz  type=16  server=119.29.29.29
   txid_matches=True rcode=0 ancount=1 bytes=65
```

注意：**记录当前的实际内容是裸 `117.183.5.29:13481`（不带 scheme）**，不是 `http://` 也不是 `tcp://`。
用户此前设置的两种值现在都不在线上。

### 1.2 原实现配置的两个 DoH 端点，实测全部不可达

| 端点 | 结果 |
| --- | --- |
| `https://cloudflare-dns.com/dns-query` | `http=000`，`curl exit=35`（TLS 握手被重置） |
| `https://dns.google/resolve` | `http=000`，`curl exit=56`（10.5 秒后连接断开） |
| `https://dns.alidns.com/resolve`（国内） | `http=200`，3.8 秒，正常返回 TXT |
| `https://doh.pub/dns-query`（国内） | `http=200`，0.32 秒，正常返回 TXT |

这就是"Windows 能查到、APP 查不到"的直接原因。

### 1.3 记录指向的端口不是 TLS 服务

```
$ curl -sS -o /dev/null -w "%{http_code}" https://117.183.5.29:13481/
curl: (35) schannel: next InitializeSecurityContext failed: SEC_E_INVALID_TOKEN

$ curl -sS -o /dev/null -w "http=%{http_code} time=%{time_total}" http://117.183.5.29:13481/
http=200 time=0.022823
```

响应头带 `X-Frame-Options: SAMEORIGIN`、`X-Content-Type-Options: nosniff`，是 Home Assistant 前端。

JVM 侧同一端口的 TLS 握手异常文本（用于校准分类器）：

```
class   = javax.net.ssl.SSLException
message = Unsupported or unrecognized SSL message
```

### 1.4 TTL 的一次"假异常"（已排除）

真实查询日志里 TTL 出现过 600 / 461 / 1 / 401 等不同值。用**同一份响应字节**做手工读取校验：

```
resp hex    = 0001818000010001...c00c 0010 0001 00000191 0013 ...
codec ttl   = 401
manual ttl at offset 40 = 401        ← 与编解码器一致
```

相隔 1 秒的两种独立实现交叉核对：

```
Kotlin (DnsMessageCodec) : ttl = 401
Python (自建解析)         : ttl = 400
```

结论：`223.5.5.5` 是 anycast 集群，不同节点缓存剩余时间不同，所以 TTL 会跳动；
**编解码器没有错**。而且 `ttlSeconds` 在 app 里只被写入、不参与任何判断，
所以这一点不影响功能，仅影响调试日志展示。

---

## 2. 修复内容

### 2.1 查询通道：从"只有 DoH"改为三级兜底

`di/EndpointModule.kt` 里的 `DnsClient` 现在是一条链，按"最可能直接可用"排序：

1. `SystemDnsClient` —— 走平台解析器 `android.net.DnsResolver`（API 29+），
   也就是**设备当前配置的 DNS**（含私有 DNS / DoT / VPN 场景）。
2. `RawUdpDnsClient` —— 用 `LinkProperties.dnsServers` 拿到设备实际 DNS，自己发 UDP 报文。
   同时覆盖 API 23–28（平台解析器查不了 TXT）。
3. `DohDnsClient` —— **国内优先**：`doh.pub` → `dns.alidns.com` → `cloudflare-dns.com` → `dns.google`，
   每个端点独立成一个链节，超时收紧到连接 3s / 读 4s。

`FallbackDnsClient` 串起这条链，规则是：

- `NOERROR` 立即返回；
- `EMPTY` / `NXDOMAIN` **不停链**（继续找第二个意见）；
- 其余状态继续下一个；
- 全部失败时，上报**最可操作**的那个结果（`EMPTY` > `NXDOMAIN` > `REFUSED` > `SERVER_FAILURE` >
  `MALFORMED` > `TIMEOUT` > `UNSUPPORTED` > `NETWORK_ERROR`）；
- 总预算 15s，超预算的解析器**不启动**并记为 `TIMEOUT`。

新增文件：

- `DnsMessageCodec.kt` —— 纯 Kotlin DNS 线格式编解码器（无 Android 依赖，可跑 JVM 单测）。
  平台 `DnsResolver` 的类型常量只有 `A`/`AAAA`/`HTTPS`，查不了 `TXT`/`SRV`，
  但 `rawQuery(Network, byte[], …)` 接受**预构造的报文**，所以自建编解码器 + 平台解析器即可全类型支持。
  实现覆盖：名称压缩指针（含跳数预算、禁止自指与前向指）、TXT 多字符字符串拼接、
  SRV RDATA 渲染、rcode 映射、TC 截断位。
- `SystemDnsClient.kt`、`RawUdpDnsClient.kt`（含 `AndroidDnsServerProvider`）、`FallbackDnsClient.kt`。

### 2.2 错误原因：不再笼统报"查询失败"

`EndpointRejectionReason` 拆分为可区分的原因，并按 `DnsQueryStatus` 一对一映射到**各自独立的中文/英文文案**：

| 状态 | 原因 | 含义 |
| --- | --- | --- |
| `NXDOMAIN` | `DNS_NAME_NOT_FOUND` | 这个名字在 DNS 里不存在 |
| `EMPTY` | `DNS_NO_RECORD_OF_TYPE` | 名字存在，但没有该类型的记录 |
| `NETWORK_ERROR` | `DNS_UNREACHABLE` | 解析器连不上 |
| `MALFORMED` | `DNS_RESPONSE_MALFORMED` | 响应畸形 |
| `SERVER_FAILURE` / `REFUSED` / `TIMEOUT` | `DNS_RESOLVER_ERROR` | 解析器报错/拒绝/超时 |
| `UNSUPPORTED` | `DNS_UNREACHABLE` | 该通道不支持 |
| TXT 全部无效 | `TXT_ALL_RECORDS_INVALID` | 记录读到了但都不可用 |
| `tcp://` 等 | `UNSUPPORTED_TRANSPORT` | 传输方式不被支持 |

同时 `EndpointResolutionError` 增加 `diagnostics` 字段，携带**解析器调用轨迹**（每个 provider 的状态与耗时），
日志只输出原因枚举名与轨迹，**不输出任何凭据**（记录在解析阶段就会拒绝内嵌 `user:password@` 的值）。

### 2.3 TXT 格式兼容与 `tcp://` 语义

- 支持的形态（逐一有测试）：`http://host:port`、`https://host:port`、`tcp://host:port`、
  裸 `host:port`、`ip=…;port=…` 字段包。
- **多字符字符串分片**：按 RFC 1035 §3.3.14，TXT 的值是分片的**纯拼接**。
  线格式解码器直接拼接；DoH JSON 路径同样**无分隔符**拼接（此前用空格拼接，是错的）。
  `117.183.5.` + `29:13481` → `117.183.5.29:13481`。
- **引号与转义**：DoH JSON 的 `"\"part1\" \"part2\""` 会被去引号、还原 `\"` 与 `\\` 后拼接。
- **`tcp://` 只声明传输端点，不假设 HTTP。** `EndpointTransport`（`HTTP`/`HTTPS`/`TCP`/`UNSPECIFIED`）
  与 `EndpointScheme` 分离，`tcp://` 解析出 `transport = TCP` 且 `scheme = null`，
  后续仍先探测 HTTPS，**不会**被直接改成 `http://` 交给 WebView。
- 未知 scheme 报 `UNSUPPORTED_SCHEME`（带 scheme 名），而不是笼统的 `INVALID_URL`。
- **删掉了"失败就去掉所有空格重试"的启发式。** 那个启发式会把 `117.183.5 29:13481`
  改写成 `117.183.529:13481`，也会把 `not a record` 变成主机名 `notarecord` ——
  等于凭空造出一个用户没配置过的地址。现在这类记录会被明确拒绝。

### 2.4 HTTPS → HTTP 降级：只依据可证明的失败类型

`EndpointProbeFailure` 拆分出 **`TLS_NOT_A_TLS_PEER`**（对端回的根本不是 TLS 记录，可证明无 HTTPS）与
**`TLS_HANDSHAKE_FAILURE`**（协商中途失败，可能只是版本不匹配，**有歧义**）。

- 只有 `CONNECTION_REFUSED`、`NO_ROUTE_TO_HOST`、`TLS_NOT_A_TLS_PEER` 算"证明 HTTPS 不存在"。
- 工作正常的 HTTPS、不受信证书、DNS 失败、认证失败、服务端 5xx：**任何情况下都不降级**。
- 有歧义的失败：**必须用户确认**，且确认后还要**明文端口真的应答**才会使用。
  明文探测在 resolver 的第二遍（Pass 2）执行，
  所以 `AUTO_DOWNGRADE` 永远不等于"猜"。

> 本轮曾额外加过一条"用户已同意 → 歧义失败直接降级"的策略分支，
> 复核后**已删除**：策略层只负责分类证据，同意与否由 Pass 2（真实探测明文端口）兑现。
> 留着它会让"有歧义"和"是证据"混在一起，正是用户要求避免的盲目降级。

明文 HTTP 是否被系统允许：`app/src/main/res/xml/network_security_config.xml` 中
`base-config cleartextTrafficPermitted="true"`，因此降级得到的 `http://` 地址可被 WebView 正常加载。

### 2.5 `txt://` 到可用地址的完整链路（含 UI）

- 地址栏支持 `txt://名字` 与 `srv://名字`，非这两种前缀时**完全走原有逻辑**（原功能不变）。
- 新增醒目提示：字段为空或正在输入记录引用时显示
  "A txt:// or srv:// address is looked up in DNS, for example txt://ha.example.com."
- 查询进行中显示 "Looking up the record…"，并禁用连接按钮（避免重复触发与看起来卡死）。
- 记录只提供明文时，不再要求用户自己把地址敲一遍：
  屏幕显示实测确认过、真实应答的那个地址，并给出
  **"Connect over http without encryption"** 按钮；点它才会带着本次同意重新查询。
- 定时刷新与手动刷新沿用上一轮已完成的
  `EndpointRefreshPolicy` / `EndpointRefreshWorker` / `EndpointForegroundRefresher`
  与设置页的刷新间隔选择 + "Refresh now"。

---

## 3. 构建结果

见 `BUILD-TEST-REPORT.md` 第 9 节（本轮追加）。此处只放关键结论。

---

## 4. 测试结果

### 4.1 单元测试（真实 Gradle 运行，非"编译成功"）

```
:common:testDebugUnitTest        BUILD SUCCESSFUL
```

动态地址解析相关 12 个测试类：

| 测试类 | 用例 |
| --- | --- |
| `DnsMessageCodecTest` | 16 |
| `DohResponseParserTest` | 14 |
| `EndpointConnectGateTest` | 10 |
| `EndpointRefreshPolicyTest` | 10 |
| `EndpointResolverTest` | 32 |
| `EndpointStateMachineTest` | 18 |
| `EndpointUrlFactoryTest` | 23 |
| `FallbackDnsClientTest` | 10 |
| `HttpsFallbackPolicyTest` | 29 |
| `SrvRecordInterpreterTest` | 18 |
| `TxtRecordInterpreterTest` | 43 |
| `RealDnsLookupTest` | 3（默认关闭，见 4.2） |

### 4.2 真实 DNS 查询测试（**这条才是"真实查询成功"**）

`RealDnsLookupTest` 默认被 `@EnabledIfEnvironmentVariable` 关闭，单独启用后跑真实网络。
本轮实测输出（原样粘贴）：

```
[real-dns] TXT hat.22345678.xyz -> NOERROR via 223.5.5.5:53
[real-dns] attempts=[223.5.5.5:53=NOERROR(94ms)]
[real-dns]   answer TXT hat.22345678.xyz ttl=… value=117.183.5.29:13481
[real-dns] candidates=[EndpointCandidate(host=117.183.5.29, port=13481, scheme=HTTPS, path=null,
             sourceRecord=117.183.5.29:13481, transport=UNSPECIFIED)]
[real-dns] rejected=[]
[real-dns] generated url=https://117.183.5.29:13481
[real-dns] pass1 success=false url=null
[real-dns] pass1 pending=http://117.183.5.29:13481
[real-dns] pass1 error=PLAINTEXT_CONFIRMATION_REQUIRED / hat.22345678.xyz resolves to
             http://117.183.5.29:13481, which does not serve HTTPS. …
[real-dns] pass2 success=true url=http://117.183.5.29:13481
[real-dns] GET http://117.183.5.29:13481 -> 200
[real-dns] A www.qq.com -> NOERROR via 223.5.5.5:53   canonical=[…, tra-kbagmtra.ias.tencent-cloud.net]
```

它依次证明：

1. 真实 UDP TXT 查询成功（`NOERROR`，94 ms）；
2. 真实响应被正确解析出候选端点；
3. 未获同意时**不降级**，而是带着实测应答的地址要求确认（`PLAINTEXT_CONFIRMATION_REQUIRED`）；
4. 同意后得到 `http://117.183.5.29:13481`；
5. 该地址**真实 GET 返回 200**，即用户最终拿到的是能用的 Home Assistant 前端；
6. 另有一条真实 `A` 查询验证 CNAME 链与名称压缩解码。

### 4.3 无法验证 / 未验证的事项（诚实声明）

1. **未在真机 / 模拟器上运行过 APP。** 本机没有可用的设备与 Emulator 镜像，所有验证都在
   JVM 侧完成（JVM 单测 + 真实网络 I/O）。因此"点一下按钮就连上"这一步**没有被真实点击验证**，
   只有它下面每一层被验证。
2. **Android 上 OkHttp/Conscrypt 抛出的 TLS 异常文本未被实测。** 我实测到的是同一端口的
   JVM JSSE 文本 `Unsupported or unrecognized SSL message`（已固化为测试用例）。
   Android 若因原生 TLS 栈给出别的文本（例如 `WRONG_VERSION_NUMBER`），会被归入**有歧义**一类，
   结果是"需要用户确认一次"而不是"静默降级"——功能仍然可用，只是多一次点击。
   **这是本方案里唯一我无法在本机闭环的环节，也是我选择保守分类的原因。**
3. `SystemDnsClient` 与 `RawUdpDnsClient` 依赖 Android API，本机无法编译进本地快速通道，
   因此它们的**代码路径**只有 Gradle 单测编译覆盖，没有网络行为验证；
   真实 DNS 网络验证走的是同一套 `DnsMessageCodec` + 原始 UDP（测试自建 `EndpointProbe` 与 UDP 交换）。
4. `:app:testMinimalDebugUnitTest` 全量在本机会因 Robolectric 沙箱死锁挂起（上一轮已记录），
   本轮未尝试全量跑；改为单独编译 app 测试源集以确认没有编译级破坏。

---

## 5. 改动文件清单

新增（生产代码）：

- `common/…/data/endpoint/DnsMessageCodec.kt`
- `common/…/data/endpoint/SystemDnsClient.kt`
- `common/…/data/endpoint/RawUdpDnsClient.kt`
- `common/…/data/endpoint/FallbackDnsClient.kt`

修改（生产代码）：

- `EndpointModel.kt` —— 新增 `EndpointTransport`、`DnsQueryStatus`、`DnsAttempt`、
  `canonicalNames`/`attemptTrail`、细分 `EndpointRejectionReason`、`pendingPlaintextEndpoint`、`diagnostics`
- `EndpointResolver.kt` —— 别名只追一次、只解释 TXT 类型答案、Pass 2 重写、细分失败原因与诊断轨迹
- `TxtRecordInterpreter.kt` —— `tcp://` 独立分支、`UNSUPPORTED_SCHEME`、删除"删空格"启发式
- `DohDnsClient.kt` —— 国内优先的多端点、超时收紧、逐 provider 轨迹、非 2xx 映射、分片无分隔符拼接
- `HttpsFallbackPolicy.kt` —— 新增 `TLS_NOT_A_TLS_PEER`、三分类 TLS 消息
- `di/EndpointModule.kt` —— 三级兜底 `DnsClient` 链
- `app/…/manualserver/ManualServerViewModel.kt` —— 进行中状态、带同意的重试、明文地址回传
- `app/…/manualserver/ManualServerScreen.kt` —— `txt://` 提示、进行中提示、"明文连接"按钮
- `common/src/main/res/values/strings.xml` —— 4 条新文案（1 条原文案改为带地址参数）

新增（测试）：`DnsMessageCodecTest.kt`、`FallbackDnsClientTest.kt`、`RealDnsLookupTest.kt`

修改（测试）：`EndpointResolverTest.kt`、`TxtRecordInterpreterTest.kt`、`HttpsFallbackPolicyTest.kt`、
`DohResponseParserTest.kt`

---

## 6. 复现步骤

```bash
# 环境（本机验证工作区，已固定 JDK21 + Android SDK + Gradle 9.8）
cd <verification>
bash run-gradle.sh --init-script <abs>/short-reports.init.gradle.kts :common:testDebugUnitTest

# 真实 DNS 查询验证（默认关闭，需要显式启用）
bash run-gradle.sh --init-script <abs>/real-dns.init.gradle.kts \
    :common:testDebugUnitTest --tests '*RealDnsLookupTest'

# APK
bash run-gradle.sh :app:assembleMinimalDebug
```

`short-reports.init.gradle.kts` 把测试报告输出改到短路径，原因是 Windows 260 字符路径限制会让
Gradle 在**测试已经全部通过之后**因删不掉旧报告而报 `BUILD FAILED`。
这不是代码问题，但会让人误以为测试失败，所以必须显式处理。
