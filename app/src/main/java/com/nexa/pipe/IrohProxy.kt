package com.nexa.pipe

import android.util.Log

object IrohProxy {
    private const val TAG = "IrohProxy"
    private var nativeLoaded = false
    
    init {
        try {
            System.loadLibrary("nexapipe_client")
            nativeInit()
            nativeLoaded = true
            Log.d(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}", e)
            nativeLoaded = false
        }
    }

    fun isNativeLoaded(): Boolean {
        return nativeLoaded
    }
    
    private external fun nativeInit(): Int

    /**
     * 注入系统 DNS 服务器列表（逗号分隔的 IP，如 "192.168.1.1,8.8.8.8"）。
     * 必须在 nativeStartIroh 之前调用。iroh 默认在 Android 上通过 JNI 读系统 DNS 会失败，
     * 回落 Google DNS（国内不稳定）。由 Kotlin 从 ConnectivityManager 拿到系统 DNS 后注入，
     * nativeStartIroh 用它构造 DnsResolver，绕开 iroh 的 JNI 失败路径。
     */
    external fun nativeSetDnsServers(dnsServers: String): Int

    /**
     * 注入 iroh 基础设施域名的预解析 IP（格式 "domain=ip1,ip2;domain2=ip3,ip4"）。
     * 必须在 nativeStartIroh 之前调用。GFW 会丢弃 iroh.link 域名的 UDP DNS 响应，
     * 导致 iroh 内部 hickory 解析 dns.iroh.link / *.relay.n0.iroh.link 超时。
     * 由 Kotlin 用系统 DNS（InetAddress，可能走 DoT/Private DNS 绕过 GFW）预解析后注入，
     * nativeStartIroh 用它构造 OverrideResolver，对这些域名直接返回预解析 IP。
     */
    external fun nativeSetDnsOverride(overrides: String): Int

    external fun nativeStartIroh(): String?
    
    external fun nativeStartProxy(listenPort: Int): Int

    /**
     * Pre-connect (warm-up): establish one iroh connection per configured
     * backend and cache it in the shared connection pool, so the first real
     * request after the VPN is up does not wait for the QUIC/relay handshake.
     * The TUN proxy and the local proxy share the same EndpointGroup / pool.
     * Must be called after nativeStartProxy and before the VPN is established.
     * @return number of backends warmed, or -1 on failure.
     */
    external fun nativePreconnect(): Int
    
    external fun nativeStartProxyLegacy(listenPort: Int, targetEndpointId: String): Int
    
    external fun nativeStopProxy(): Int
    
    external fun nativeAddNode(nodeId: String, domains: String): Int
    
    external fun nativeAddDomainMapping(domain: String, nodeId: String): Int
    
    external fun nativeRemoveNode(nodeId: String): Int
    
    external fun nativeClearNodes(): Int
    
    external fun nativeAddDomain(domain: String): Int
    
    external fun nativeRemoveDomain(domain: String): Int
    
    external fun nativeDestroy(): Int

    /**
     * 启动 TUN 代理：用 smoltcp 在 Rust 侧处理 TUN fd 的 TCP/UDP 流量。
     *
     * 必须在 nativeStartProxy 之后（endpoint_group 已创建）、VPN 建立之后调用。
     * Kotlin 侧通过 ParcelFileDescriptor.detachFd() 将 fd 所有权转移给 Rust，
     * Rust 侧 dup 两份（读/写）后关闭原始 fd。
     *
     * @param tunFd ParcelFileDescriptor.detachFd() 返回的原始 fd
     * @param proxyDomains 逗号分隔的代理域名列表
     * @param captivePortalDomains 逗号分隔的 captive portal 校验域名列表
     * @return 0 成功，-1 失败
     */
    external fun nativeStartTunProxy(
        tunFd: Int,
        proxyDomains: String,
        captivePortalDomains: String
    ): Int

    /**
     * 停止 TUN 代理：abort 所有后台任务，关闭 dup 的 fd。
     * 由 NexaVpnService.stopVPN() 在关闭 TUN fd 之前调用。
     */
    external fun nativeStopTunProxy(): Int
}