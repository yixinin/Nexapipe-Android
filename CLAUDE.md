# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Develop

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests (JVM, no device needed)
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run a single unit test class
./gradlew test --tests "com.nexa.pipe.ExampleUnitTest"

# Clean build
./gradlew clean

# View app logs (PowerShell)
.\logcat.ps1
```

The project targets **arm64-v8a only** (no x86 or 32-bit ARM). A physical device or arm64 emulator image is required.

## Architecture

This is an Android VPN-based proxy app that routes traffic through the [Iroh](https://iroh.computer) peer-to-peer protocol. It works by creating a local TUN interface, intercepting DNS and TCP, and forwarding through a local SOCKS-style proxy to Iroh nodes.

### Layer stack (bottom-up)

1. **Native library** (`app/src/main/jniLibs/arm64-v8a/libnexapipe_client.so`) — Core iroh/proxy engine, accessed via JNI.

2. **`IrohProxy`** — Singleton JNI wrapper. Loads the `.so` on init, exposes `native*` functions: `nativeStartIroh()`, `nativeStartProxy(port)`, `nativeAddDomainMapping(domain, nodeId)`, `nativeClearNodes()`, etc. All calls are synchronous and should be called from `Dispatchers.IO`.

3. **`NexaVpnService`** — Android `VpnService` (foreground). Creates a TUN device on subnet `10.0.1.0/24`:
   - **DNS interception**: UDP packets to `10.0.1.2:53` are inspected. For proxied domains, responds with `10.0.1.3` (virtual proxy IP). For other domains, resolves via real DNS.
   - **TCP proxy**: TCP packets to `10.0.1.3:80/443` are forwarded to `127.0.0.1:<proxyPort>`. The service implements raw TCP state machines (SYN → SYN-ACK → data with PSH+ACK → FIN) with correct sequence/acknowledgment number tracking, constructing IP/TCP packets in byte arrays.
   - Uses `setUnderlyingNetworks` to bind to the physical network (WiFi/cellular), avoiding routing loops. Excludes its own package via `addDisallowedApplication`.
   - Managed via intents (`ACTION_START`/`ACTION_STOP`), not direct binding.

4. **`VpnViewModel`** — Orchestrator that sequences the connection flow:
   1. Start iroh node via `IrohProxy.nativeStartIroh()`
   2. Clear existing nodes, add domain→node mappings via `IrohProxy.nativeAddDomainMapping()`
   3. Start local proxy via `IrohProxy.nativeStartProxy(port)` (retries up to 10 ports if occupied)
   4. Launch `NexaVpnService` via `startForegroundService(intent)` with the port and domain list
   5. On disconnect: stop service, call `nativeStopProxy()`

5. **`SettingsManager`** — Persists `List<NodeConfig>` (node IDs + associated domains) and proxy port via `SharedPreferences` + `kotlinx-serialization-json`.

6. **UI** (Jetpack Compose + Material 3) — `MainActivity` hosts `VpnControlScreen` and `PermissionGuideScreen`. State flows through `VpnViewModel` via `MutableStateFlow`.

### Key constraints

- **VPN permission must be granted before starting** — the app guides the user through system VPN consent dialog.
- **Domain mappings are mandatory** — connecting without at least one node with associated domains will fail.
- **`iroh.link` and `n0.iroh.link` domains are never proxied** — they are hardcoded as bypass in `NexaVpnService.shouldProxyDomain()`.
- The native library is **not reloadable** — once `System.loadLibrary` succeeds, it stays loaded for the process lifetime. Avoid calling `nativeDestroy()` casually.
