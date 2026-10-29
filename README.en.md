# DSH Pal

An independently developed Android companion for DeepSeek Harness: open your DSH web workspace on a phone or tablet, follow task progress in notifications, and manage remote connections.

[中文](README.md) · [Connection guide (Chinese)](docs/connections.md) · [Maintainer handoff (Chinese)](docs/PROJECT-HANDOFF.md) · [Third-party notices](THIRD_PARTY_LICENSES.md)

## What it does

DSH Pal embeds the DSH web workspace rather than implementing a separate chat client. The native layer adds connection setup, system file selection and downloads, mobile/tablet layout adjustments, themes, resource caching, task notifications and diagnostics.

The computer must run DSH and the remote-web-ui plugin. This app does not run the desktop agent or model locally.

Supported entry types include Relay, Cloudflare Tunnel, custom HTTPS, Tailscale, WireGuard, Wi-Fi entry profiles and a development-only USB route. Each requires its corresponding reachable infrastructure; a configured VPN is not the same as an authenticated DSH session.

Task notifications show public tool-step descriptions when collapsed, and brief public context plus recent steps when expanded. Reasoning, command bodies and tool-result bodies are excluded. A MediaStyle/MediaSession island option is available on compatible ROMs and is off by default; there is no private Xiaomi focus-protocol bypass.

## Verification and known limitations

At the 2026-10-29 sidebar baseline, 175 Kotlin unit tests and the JavaScript layout checks passed. Phone and tablet were tested separately with actual touch gestures over the USB development route, including independent workspace/outer-sidebar scrolling. The phone right drawer can reopen and releases chat scrolling when closed.

A subsequent screen-off launch on the tablet also produced a foreground-service startup timeout; its cause has not been isolated. The latest normal-release public-network check did **not** complete successfully. Timeouts and probe inconsistencies remain under investigation. Historical Tailscale end-to-end success is not a guarantee for the current network. WireGuard has no real-server acceptance result yet. Tablet expanded notification details, heavy-load frame performance and some plugin combinations still need verification.

## Build

Use JDK 21, Android SDK 36 and an ASCII project path. Keep Kotlin 2.3.20, AGP 8.13.2, Gradle 8.14.4 and Miuix 0.8.8. Do not add a Compose BOM or move to Miuix 0.9.x.

Prepare the embedded Tailscale library as documented in [third_party/tailscale-bindings/README.md](third_party/tailscale-bindings/README.md). The generated library is not committed; a clean-machine/CI build still needs acceptance.

```text
./gradlew :app:assembleRelease :app:testDebugUnitTest
node tools/test-web-layout-syntax.mjs
node tools/test-web-right-layout.mjs
node tools/test-web-drawer.mjs
node tools/test-web-viewport.mjs
```

Release signing uses an untracked local keystore.properties; without it the build falls back to the debug key. Use a stable production key before distributing updates. Preserve existing app data with adb install -r.

## Privacy and licensing

Notification previews can contain short public task descriptions; review lock-screen settings. Diagnostics are bounded and sanitized, but review exported files before publishing. Never share pairing tokens, cookies, account authorization URLs, private keys or private conversations.

There is no bundled advertising or analytics SDK. Depending on the selected route, traffic can involve Tailscale, identity providers, Cloudflare or Relay infrastructure. The embedded VPN can compete with another Android VPN.

Application code: [Apache-2.0](LICENSE). Whale-girl artwork: **CC BY-NC-SA 4.0**, with attribution and noncommercial restrictions described in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). Fonts and bundled components retain their respective licenses.

Thanks to DSH, the remote-web-ui plugin, [DSHgo](https://github.com/just-a-FA-for-name/DSHgo), Miuix and the upstream projects. This is not an official DeepSeek, DSH or Xiaomi client.

For an issue, include app/Android/ROM/WebView versions, route, relevant plugins, screen mode, reproduction steps and sanitized diagnostics. Public project/community links are not configured yet.
