> **⚠️ Outdated document, kept for history.**
> Start with **[`README-动态地址解析.md`](README-动态地址解析.md)** (Chinese) instead: it covers the
> unified TXT + SRV resolver, the settings screen and the rewritten GitHub Actions workflow.

# TXT build quick start

This repository is the Home Assistant Android source with an initial TXT URL resolver and a GitHub Actions workflow.

- Read `README-TXT-GITHUB.md` for setup and limitations.
- Run **Actions → Build HA Android dynamic address APK → Run workflow** to build an APK.
- Download the `home-assistant-dynamic-address-minimal-debug-apk` artifact from the completed run.
- To verify locally instead, see §9.1 of `README-动态地址解析.md` for the exact commands and measured
  results (`:common:testDebugUnitTest`, `--write-locks`, `:app:assembleMinimalDebug`, static inspection).
