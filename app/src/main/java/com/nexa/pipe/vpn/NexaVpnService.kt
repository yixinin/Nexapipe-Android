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
import kotlinx.coroutines.launch

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
            try {
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
                    isRunning = false
                    return@launch
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
                    isRunning = false
                    return@launch
                }

                Log.d(TAG, "VPN + TUN proxy established successfully (routing 10.0.1.0/24)")
                createNotificationChannel()
                startForeground(1, createNotification())
            } catch (e: Exception) {
                Log.e(TAG, "VPN establishment failed: ${e.message}", e)
                isRunning = false
            }
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
                    underlyingNetwork = network
                    Log.d(TAG, "Underlying network updated: $network (VPN: $hasVPN, Internet: $hasInternet)")
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                if (underlyingNetwork == network) {
                    underlyingNetwork = null
                    Log.d(TAG, "Underlying network lost: $network")
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
    }
}
