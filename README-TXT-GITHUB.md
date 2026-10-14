> **⚠️ Outdated document, kept for history.**
> The initial TXT resolver described here has been replaced by a unified TXT + SRV resolver.
> Read **[`README-动态地址解析.md`](README-动态地址解析.md)** (Chinese) for the current design,
> build steps and acceptance status.

# Home Assistant Android — TXT URL build

This source tree is based on the Home Assistant Android project and includes an initial `txt://` URL resolver in the manual server onboarding flow.

## TXT input

On the manual server URL screen, enter:

```text
txt://ha.example.com
```

The app queries TXT records through Cloudflare DNS-over-HTTPS and converts supported TXT values into an HTTP(S) URL. Supported examples:

- `https://ha.example.com:8123`
- `203.0.113.10:8123`
- `ip=203.0.113.10;port=8123`
- `host=ha.example.com;port=8123`
- `url=https://ha.example.com:8123`

The IP addresses above are documentation examples only.

## Build with GitHub Actions

1. Create a new GitHub repository. Do not upload this ZIP as a single file.
2. Extract the ZIP on your computer.
3. Upload **the contents inside** the extracted `android-main` directory to the repository root, including the `.github` directory and `gradlew` file. Make sure `.github/workflows/build-txt-apk.yml` exists in the repository.
4. Commit and push to `main` or `master`, or open the repository's **Actions** tab and run **Build HA Android TXT APK** using **Run workflow**.
5. Open the completed workflow run and download the artifact named `home-assistant-txt-minimal-debug-apk`.
6. Extract the artifact ZIP to get the APK and install it on your Android device.

No GitHub secrets are required for this debug build. The workflow builds the `minimalDebug` variant and uploads the APK as an Actions artifact; it does not publish a GitHub Release.

## Important limitations

- TXT is resolved when manually adding a server. This initial patch does not refresh a saved server URL continuously when the TXT record changes.
- This only converts TXT content into an HTTP(S) URL. It does not implement Lucky STUN hole-punching. If the TXT value contains STUN-specific connection parameters rather than a directly reachable HTTP(S) endpoint, additional protocol support is required.
- DNS lookup uses `https://cloudflare-dns.com/dns-query`, so the device needs access to Cloudflare's DNS-over-HTTPS endpoint.
- This modified source has not yet been successfully built or device-tested in the preparation environment. Use the Actions result to verify the actual build. If it fails, open the workflow log and inspect the first Gradle compilation/configuration error.

## Upstream project

Home Assistant Android: https://github.com/home-assistant/android

Review the upstream project's license and notices before redistributing modified builds.
