# Debug Session: VPN Connect Timeout

**Session ID:** vpn-connect-timeout
**Status:** [OPEN]
**Started:** 2026-07-08

## Symptoms

- VPN shows as "connected" in UI
- Cannot access `fn.iroh.iakl.top`
- Logcat shows: `FrameInsert open fail: No such file or directory`
- Logcat shows: `Error sending packet ... ETIMEDOUT (Connection timed out)` when connecting to `216.239.36.223:443`

## Environment

- Android targetSDK: 36
- Device: Xiaomi (MIUI)
- App package: `com.nexa.pipe`
- Local proxy port: 8080

## Hypotheses

1. **H1 - Traffic not routed through HTTP proxy:** The VPN service intercepts all IP packets but does not correctly forward proxied domains to the local HTTP proxy (127.0.0.1:8080).
2. **H2 - Incorrect TCP state management:** The VPN service creates a new socket for every IP packet instead of maintaining persistent TCP connections, breaking the TCP stream.
3. **H3 - Missing HTTP CONNECT handshake:** When forwarding TCP payload to the local proxy, the VPN service omits the required HTTP CONNECT handshake.
4. **H4 - Non-proxied traffic timeout:** All traffic is routed into the VPN; non-proxied packets are forwarded via `protect()` socket but still timeout due to routing or network issues.
5. **H5 - VPN interface creation failure:** `FrameInsert open fail` indicates the system could not create the VPN tun interface, possibly due to foreground service type or timing issues.

## Applied Fixes

1. VPN now routes only the virtual IP range `198.18.0.0/15`, so non-proxied traffic no longer enters the VPN and cannot timeout inside it.
2. Added `VpnService.Builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", proxyPort))` so Android routes HTTP/HTTPS traffic from proxy-aware apps directly to the Rust local proxy.
3. DNS handler returns a virtual IP for configured domains and forwards other queries to real DNS.
4. TCP handler maintains persistent sockets and sends an HTTP CONNECT handshake before forwarding payload to the local proxy.
5. DNS forwarding now uses the active network's DNS servers instead of hard-coded 8.8.8.8, with fallback and retry.
6. DNS servers are now cached before the VPN interface is established, preventing the VPN's own virtual DNS (198.18.0.1) from being used as an upstream.

## Next Steps

- Reinstall and test accessing `fn.iroh.iakl.top` in a browser or other proxy-aware app.
- Collect logcat filtered by `tag:NexaVpnService` if the issue persists.
