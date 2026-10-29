# Tailscale JNI bridge

The native engine is extracted, unmodified, from **Tailscale's official Android 1.104.2 universal APK**, not from DSHgo. The generated AAR is local and excluded from Git. Run `tools/prepare-tailscale-sdk.ps1` before building.

- Source: https://pkgs.tailscale.com/stable/tailscale-android-universal-1.104.2.apk
- SHA-256: `270b3e6e54c99103ed98fa967de7bbf6053e1066250ea35d2b9f450947fd0a78`, verified against the publisher's `.sha256` file.
- ARM64 load-segment alignment: 16,384 bytes. The 4,096-byte engine in the earlier DSHgo reference APK is deliberately not used.
- Upstream source: https://github.com/tailscale/tailscale-android/tree/v1.104.2 and https://github.com/tailscale/tailscale.

These 21 Java files are gomobile-generated JNI declarations and Go reference-counting glue, recovered from the user-supplied DSHgo 1.2 reference package (`8d036ae75c9494b14e91f42c5a3f4ecca65a7dd8a257e44c2061674f2d0a94c5`). Its public JNI declarations were compared with official 1.104.2 declarations and match. They contain no DSHgo UI, credentials, VPN configuration or application business code. Keep this compatibility boundary version-pinned and test engine initialization, LocalAPI and VPN operations before an SDK update.

Tailscale code is BSD-3-Clause; gomobile glue is covered by the Go Authors' BSD license. See the adjacent license files. The application uses no Tailscale marks as its own identity, does not implement the cryptographic protocol, and does not modify the native engine.
