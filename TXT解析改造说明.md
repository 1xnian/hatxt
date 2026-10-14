> **⚠️ 本文档已过时（历史存档）。**
> 这里描述的初版 TXT 实现（`TxtRecordUrlResolver`）已被**完整重构并删除**，取而代之的是同时支持
> TXT 与 SRV 的统一解析模块。请阅读 **[`README-动态地址解析.md`](README-动态地址解析.md)**。
> 旧实现的问题与新架构的说明见该文档的「旧实现去哪了」一节。

# Home Assistant Android TXT 解析改造（初版）

基于上传的 Home Assistant Android 官方源码，增加了在首次手动添加服务器时使用 `txt://` 前缀查询 DNS TXT 并转换为 HTTP(S) 地址的能力。

## 使用方式

在 HA 安卓客户端的“手动输入服务器地址”页面输入：

`txt://ha.example.com`

客户端通过 Cloudflare DNS-over-HTTPS 查询 `ha.example.com` 的 TXT 记录，然后将记录内容转换为服务器 URL，再进入原有连接流程。

## 当前支持的 TXT 值格式

- `https://ha.example.com:8123`
- `http://203.0.113.10:8123`
- `203.0.113.10:8123`（自动补 `http://`）
- `ip=203.0.113.10;port=8123`
- `host=ha.example.com;port=8123`
- `url=https://ha.example.com:8123`

以上地址均为示例。

## 重要限制

- 当前版本在手动添加服务器时解析一次 TXT；不会在运行过程中持续监测 TXT 并自动更新已保存服务器的 URL。
- 如果 TXT 保存的是 STUN 打洞参数而不是可直接访问的 HTTP(S) 地址，本功能不能代替 STUN 客户端或 NAT 打洞协议。
- DNS 查询使用 Cloudflare DNS-over-HTTPS，因此手机网络必须能访问 `cloudflare-dns.com`。
- 此次环境尝试执行 `./gradlew :app:assembleMinimalDebug --no-daemon`，但构建因无法解析 `services.gradle.org`（`UnknownHostException`）失败；当前环境也没有 Android SDK。因此没有生成或伪造 APK。请在有网络与 Android SDK 的构建机上执行：

```bash
./gradlew :app:assembleMinimalDebug
```

或构建带 Google Play Services 的版本：

```bash
./gradlew :app:assembleFullDebug
```

APK 通常输出在 `app/build/outputs/apk/` 下。
