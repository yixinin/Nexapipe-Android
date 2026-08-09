package com.nexa.pipe.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nexa.pipe.IrohProxy
import com.nexa.pipe.MainActivity
import com.nexa.pipe.R
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * VPN 服务：创建 TUN 接口并将 fd 传给 Rust 侧的 smoltcp TUN 代理。
 *
 * 原 ~1600 行手写 TCP/IP 栈已删除，替换为 Rust netstack-smoltcp 用户态栈。
 * Kotlin 侧只负责：VPN 建立、网络回调、前台服务、传递 TUN fd 给 Rust。
 *
 * 数据流：APP → TUN fd → Rust(smoltcp) → handle_local_connection → iroh → 后端
 * DNS 劫持、captive portal 204 响应、TCP MSS/重传/FIN-ACK 全部在 Rust 侧处理。
 */
class NexaVpnService : VpnService() {
    private val TAG = "NexaVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var allowedDomains = mutableSetOf<String>()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var underlyingNetwork: Network? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isUserStarted = false

    // 网络切换重连：记录重连任务与互斥，避免并发 native 调用竞态。
    private var reconnectJob: Job? = null
    @Volatile private var reconnectInProgress = false
    // TUN 代理是否已成功启动（仅在此为 true 时才对网络切换触发重连，
    // 避免在初始建立 VPN 的过程中误触发）。
    @Volatile private var tunProxyStarted = false
    // 当前底层网络是否已丢失（onLost 置位，恢复可用时用于触发重建；避免
    // 初始建立 VPN 时底层网络尚未上报导致的误触发）。
    @Volatile private var networkLost = false
    private val reconnectMutex = Any()

    // TUN 子网配置（必须与 Rust tun_proxy.rs 的虚拟 IP 常量一致）
    private val virtualDNSIP = "10.0.1.2"
    private val tunInterfaceIP = "10.0.1.1"
    // virtualProxyIP(10.0.1.3) 和 virtualCaptivePortalIP(10.0.1.4) 在 Rust 侧硬编码，
    // 这里不需要——DNS 响应由 Rust 构造，TCP 分流由 Rust 按 local_addr.ip() 判断。

    // Android/MIUI 联网校验域名。运营商 DNS 常劫持这些域名到 captive portal IP，
    // 导致系统判定无网络/感叹号。Rust 侧将它们 DNS 劫到 10.0.1.4，TCP 直接回 204。
    private val captivePortalDomains = setOf(
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "connectivitycheck.google.com",
        "connect.rom.miui.com",
        "connect.rom.miui.jp",
        "connect.vip.miui.com",
        "wifi.vip.miui.com",
        "www.google.com"
    )

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVPN()
        unregisterNetworkCallback()
    }

    fun startVPN(domains: Set<String>) {
        this.allowedDomains = domains.toMutableSet()
        isUserStarted = true

        val prepareIntent = prepare(this)
        if (prepareIntent != null) {
            Log.d(TAG, "VPN permission not granted")
            return
        }

        // ACTION_START 表示 VpnViewModel 要求重新建立 VPN（含新 TUN fd）。
        // nativeStopProxy（connect 流程中 startProxyWithRetries 调用）已停掉了
        // Rust 侧 TUN 代理，但 isRunning 可能仍为 true（VPN 服务未被 stopVPN 停止）。
        // 必须重置 isRunning，否则 establishVPN 会因 "VPN already running" 跳过，
        // 导致 TUN 代理永远不会重建 → 数据平面死掉（表现为 "连上就断"）。
        synchronized(this@NexaVpnService) {
            if (isRunning) {
                Log.d(TAG, "startVPN: resetting isRunning (TUN proxy was stopped by nativeStopProxy)")
                isRunning = false
                tunProxyStarted = false
            }
        }
        // 取消可能挂起的网络切换重连，避免与新的建立流程竞态。
        reconnectJob?.cancel()
        reconnectJob = null

        establishVPN()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_START -> {
                    val domains = intent.getStringArrayListExtra(EXTRA_DOMAINS) ?: emptyList()
                    startVPN(domains.toSet())
                }
                ACTION_STOP -> {
                    stopVPN()
                }
            }
        }
        return START_STICKY
    }

    fun stopVPN() {
        isRunning = false
        isUserStarted = false
        tunProxyStarted = false
        networkLost = false
        isServiceActive = false
        // 取消进行中的网络切换重连，避免与手动断开竞态。
        reconnectJob?.cancel()
        reconnectJob = null

        // 先停 TUN 代理（abort smoltcp 任务 + 关闭 dup 的 fd → VPN 自动拆除）
        // 必须在 vpnInterface 处理之前调用，因为 fd 所有权已转给 Rust。
        try {
            IrohProxy.nativeStopTunProxy()
        } catch (e: Exception) {
            Log.e(TAG, "nativeStopTunProxy failed: ${e.message}")
        }

        // vpnInterface 已 detachFd，fd 所有权在 Rust 侧，不能再 close()
        // 只需清除引用
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun establishVPN() {
        synchronized(this@NexaVpnService) {
            if (isRunning) {
                Log.d(TAG, "VPN already running, skipping")
                return
            }
            isRunning = true
        }

        serviceScope.launch {
            if (!establishVpnInternal()) {
                Log.d(TAG, "VPN establishment failed")
                isRunning = false
            }
        }
    }

    /**
     * 建立 VPN + TUN 代理。可被初始建立与网络切换重连复用。
     * @return true 成功；false 失败（失败时由调用方处理 isRunning 状态）。
     */
    private suspend fun establishVpnInternal(): Boolean {
        return try {
            val builder = Builder()
                .setSession("Nexa VPN")
                .addAddress(tunInterfaceIP, 24)
                // 只路由虚拟 IP 段 (10.0.1.0/24)
                // DNS 查询（到 10.0.1.2:53）走 TUN
                // TCP 代理流量（到 10.0.1.3:80/443）走 TUN
                .addRoute("10.0.1.0", 24)
                .addDnsServer(virtualDNSIP)
                // 排除自身 APP 流量，确保代理连接走物理网络
                .addDisallowedApplication(packageName)

            if (underlyingNetwork != null) {
                builder.setUnderlyingNetworks(arrayOf(underlyingNetwork))
                Log.d(TAG, "Set underlying network: $underlyingNetwork")
            } else {
                Log.d(TAG, "No underlying network from callback, skipping setUnderlyingNetworks")
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.d(TAG, "Failed to establish VPN")
                return false
            }

            // 将 TUN fd 所有权转移给 Rust（detachFd 后 PFD 不再可用）
            val fd = vpnInterface!!.detachFd()
            vpnInterface = null  // PFD 已失效，清除引用

            val proxyDomainsStr = allowedDomains.joinToString(",")
            val portalDomainsStr = captivePortalDomains.joinToString(",")

            Log.d(TAG, "Starting TUN proxy: fd=$fd, " +
                    "proxyDomains=${allowedDomains.size} items, " +
                    "portalDomains=${captivePortalDomains.size} items")

            val result = IrohProxy.nativeStartTunProxy(
                fd, proxyDomainsStr, portalDomainsStr
            )

            if (result != 0) {
                Log.e(TAG, "Failed to start TUN proxy: $result, closing fd")
                // nativeStartTunProxy 失败时 fd 未被 Rust 接管，需手动关闭
                try {
                    ParcelFileDescriptor.adoptFd(fd).close()
                } catch (_: Exception) {}
                tunProxyStarted = false
                return false
            }

            tunProxyStarted = true
            isServiceActive = true
            Log.d(TAG, "VPN + TUN proxy established successfully (routing 10.0.1.0/24)")
            createNotificationChannel()
            startForeground(1, createNotification())
            true
        } catch (e: Exception) {
            Log.e(TAG, "VPN establishment failed: ${e.message}", e)
            tunProxyStarted = false
            false
        }
    }

    // ============================================================
    // 网络回调 — 检测 underlyingNetwork（WiFi/蜂窝）
    // ============================================================

    private fun registerNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val capabilities = connectivityManager?.getNetworkCapabilities(network)
                val hasVPN = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false
                val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false

                if (!hasVPN && hasInternet) {
                    // 仅在尚无底层网络时记录（供初始 setUnderlyingNetworks 使用）。
                    // 不要在任何新网络出现时都触发重建：WiFi/蜂窝并存时，新网络
                    // 出现不代表当前网络已切换，立即重建会让隧道频繁断线。
                    // 真正的切换由 onLost（当前底层网络丢失）触发。
                    if (underlyingNetwork == null) {
                        underlyingNetwork = network
                        Log.d(TAG, "Underlying network set: $network (VPN: $hasVPN, Internet: $hasInternet)")
                        // 之前在等待网络恢复（onLost 已清空底层网络）→ 触发重建恢复隧道。
                        if (networkLost) {
                            networkLost = false
                            onUnderlyingNetworkChanged("underlying network recovered: $network")
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                if (underlyingNetwork == network) {
                    underlyingNetwork = null
                    networkLost = true
                    Log.d(TAG, "Underlying network lost: $network")
                    onUnderlyingNetworkChanged("underlying network lost: $network")
                }
            }
        }

        // 不要求 NET_CAPABILITY_VALIDATED：国内运营商常劫持 connectivitycheck
        // 导致 WiFi 无法 validated → onAvailable 不触发 → underlyingNetwork 始终 null。
        // Rust 侧的 captive portal 204 响应会让系统校验通过。
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        connectivityManager?.registerNetworkCallback(request, networkCallback as ConnectivityManager.NetworkCallback)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }
    }

    // ============================================================
    // 网络切换重连 — 底层网络（WiFi/蜂窝）变化时重建整条隧道
    // ============================================================

    private fun onUnderlyingNetworkChanged(reason: String) {
        // 仅在用户已启动且 TUN 代理已建立时触发，避免初始建立过程误触发。
        if (!isUserStarted || !isRunning || !tunProxyStarted) {
            Log.d(TAG, "Network change ignored ($reason): session not fully up")
            return
        }
        Log.d(TAG, "Network change detected ($reason), scheduling reconnect")
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        // 防抖：连续的 onLost/onAvailable 只保留最后一次，并给新网络一点稳定时间。
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(RECONNECT_DEBOUNCE_MS)
            reconnectTunnel()
        }
    }

    private suspend fun reconnectTunnel() {
        if (!isUserStarted || !isRunning) return
        synchronized(reconnectMutex) {
            if (reconnectInProgress) return
            reconnectInProgress = true
        }
        try {
            // 切换尚未完成（如进了飞行模式）：等下一个网络事件再触发。
            if (underlyingNetwork == null) {
                Log.d(TAG, "Reconnect: no underlying network yet, skipping")
                return
            }
            if (!IrohProxy.isNativeLoaded()) {
                Log.e(TAG, "Reconnect: native library not loaded")
                return
            }
            Log.d(TAG, "Reconnect: rebuilding tunnel on underlying network $underlyingNetwork")

            var lastError: Exception? = null
            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                if (!isUserStarted || !isRunning) return
                try {
                    withTimeout(RECONNECT_ATTEMPT_TIMEOUT_MS) {
                        rebuildTunnel()
                    }
                    Log.d(TAG, "Reconnect complete (attempt $attempt/$MAX_RECONNECT_ATTEMPTS)")
                    return
                } catch (e: TimeoutCancellationException) {
                    lastError = e
                    Log.e(TAG, "Reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS timed out")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 手动断开/服务销毁主动取消：不重试。
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    Log.e(TAG, "Reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS failed: ${e.message}")
                }
                if (attempt < MAX_RECONNECT_ATTEMPTS) {
                    delay(RECONNECT_BACKOFF_MS)
                }
            }
            Log.e(TAG, "Reconnect: all attempts failed: ${lastError?.message}")
            // 保留服务运行；Rust 侧请求级重试会兜底，下次网络变化再触发重连。
        } finally {
            synchronized(reconnectMutex) { reconnectInProgress = false }
        }
    }

    /**
     * 重建隧道：停旧 TUN 代理 → 用新 underlying network 重建 VPN + TUN 代理。
     *
     * iroh endpoint / 本地代理保持不动：iroh 自带路径迁移与 relay 重连，
     * 连接池会按需新建后端连接。不要销毁重建 endpoint——那会带来数秒到数十秒
     * 的断线窗口（nativeStartIroh 弱网下可阻塞 30s），并可能在重建失败时
     * 把隧道留在不可用状态。
     */
    private suspend fun rebuildTunnel() {
        // 1. 停旧 TUN 代理（关闭 dup fd → 旧 VPN 自动拆除）
        runCatching { IrohProxy.nativeStopTunProxy() }
            .onFailure { Log.e(TAG, "Reconnect: nativeStopTunProxy failed: ${it.message}") }
        tunProxyStarted = false

        // 2. 重建 VPN（新 TUN fd + 新 underlying network）并启动 TUN 代理。
        if (!establishVpnInternal()) {
            throw Exception("Reconnect: failed to re-establish VPN/TUN proxy")
        }
    }

    // ============================================================
    // 通知
    // ============================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Nexa VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Nexa VPN Service"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Nexa VPN")
            .setContentText("Connected")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "NexaVPN"
        const val ACTION_START = "com.nexa.pipe.vpn.ACTION_START"
        const val ACTION_STOP = "com.nexa.pipe.vpn.ACTION_STOP"
        const val EXTRA_DOMAINS = "com.nexa.pipe.vpn.EXTRA_DOMAINS"

        // 网络切换重连参数
        private const val RECONNECT_DEBOUNCE_MS = 1_500L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_ATTEMPT_TIMEOUT_MS = 60_000L
        private const val RECONNECT_BACKOFF_MS = 2_000L

        /**
         * 进程级标志：VPN 服务是否处于活动状态（TUN 代理已建立）。
         * 用于 ViewModel 在 Activity 重建后同步 UI 状态，避免 UI 显示 "Disconnected"
         * 但 VPN 服务实际仍在运行的不一致。
         */
        @Volatile
        @JvmStatic
        var isServiceActive: Boolean = false
            private set
    }
}
