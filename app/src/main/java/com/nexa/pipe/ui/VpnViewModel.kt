package com.nexa.pipe.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.pipe.IrohProxy
import com.nexa.pipe.PermissionManager
import com.nexa.pipe.SettingsManager
import com.nexa.pipe.vpn.NexaVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

import kotlinx.serialization.Serializable

@Serializable
data class NodeConfig(
    val nodeId: String,
    val domains: List<String> = emptyList()
)

class VpnViewModel : ViewModel() {
    private val TAG = "VpnViewModel"
    private var settingsManager: SettingsManager? = null

    val isVpnRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isIrohStarted = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isConnecting = kotlinx.coroutines.flow.MutableStateFlow(false)
    val endpointId = kotlinx.coroutines.flow.MutableStateFlow("")
    val nodes = kotlinx.coroutines.flow.MutableStateFlow(mutableListOf<NodeConfig>())
    val logMessages = kotlinx.coroutines.flow.MutableStateFlow(mutableListOf<String>())
    val errorMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val connectionStatusText = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val vpnPermissionGranted = kotlinx.coroutines.flow.MutableStateFlow(false)
    val notificationPermissionGranted = kotlinx.coroutines.flow.MutableStateFlow(false)

    // 串行化 connect/disconnect，避免并发 native 调用竞态。
    private val connectionMutex = Mutex()
    // 持有 connect 协程句柄，disconnect 时可取消（JNI 不可中断，但下一挂起点会抛 CancellationException）。
    private var connectJob: Job? = null

    companion object {
        // 单次连接尝试的超时与重试参数。弱网下 iroh bind 可达 30s，留足预算。
        private const val MAX_CONNECT_ATTEMPTS = 3
        private const val ATTEMPT_TIMEOUT_MS = 60_000L
        private const val DISCONNECT_MUTEX_TIMEOUT_MS = 70_000L
        private val BACKOFF_MS = longArrayOf(0, 1_000, 2_000)
        // local proxy 监听端口（仅供 preConnect 预热用，TUN 模式下数据不走 local proxy）。
        // 端口冲突时 startProxyWithRetries 会自动递增。
        private const val LOCAL_PROXY_PORT = 8080
    }

    fun initSettings(context: android.content.Context) {
        if (settingsManager == null) {
            settingsManager = SettingsManager(context)
        }
    }

    fun loadSettings() {
        settingsManager?.let { manager ->
            val loadedNodes = manager.loadNodes()
            nodes.value = loadedNodes.toMutableList()
            addLog("Settings loaded: ${loadedNodes.size} nodes")
        }
    }

    private fun saveSettings() {
        settingsManager?.let { manager ->
            manager.saveNodes(nodes.value)
        }
    }

    fun addLog(message: String) {
        Log.d(TAG, message)
        viewModelScope.launch {
            logMessages.value.add(message)
            if (logMessages.value.size > 100) {
                logMessages.value.removeFirst()
            }
            logMessages.value = logMessages.value.toMutableList()
        }
    }

    fun startIroh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureIrohStarted()
            } catch (e: Exception) {
                addLog("Error starting iroh: ${e.message}")
                errorMessage.value = e.message ?: "Unknown error"
            }
        }
    }

    /**
     * 启动 iroh endpoint（若未启动）。nativeStartIroh 在 Rust 侧已有 30s 超时。
     * 抛异常表示失败，由调用方决定是否重试。
     */
    private suspend fun ensureIrohStarted() {
        if (isIrohStarted.value) return
        addLog("Starting iroh first...")
        val id = IrohProxy.nativeStartIroh()
        if (id != null) {
            endpointId.value = id
            isIrohStarted.value = true
            addLog("Iroh started: $id")
        } else {
            throw Exception("Failed to start iroh")
        }
    }

    /**
     * 从 ConnectivityManager 获取系统 DNS 服务器列表（逗号分隔的 IP 字符串）。
     *
     * iroh 默认在 Android 上通过 JNI 读取系统 DNS 会失败（Null pointer in call_method
     * obj argument），回落到 Google DNS（8.8.8.8/8.8.4.4），国内不稳定导致后端连不上。
     * 这里由 Kotlin 直接从 ConnectivityManager.getLinkProperties().dnsServers 获取系统 DNS，
     * 传给 Rust 侧 nativeSetDnsServers，让 iroh 用自定义 DnsResolver 而非 JNI 路径。
     *
     * 跳过 TRANSPORT_VPN 网络，只取底层 WiFi/蜂窝网络的 DNS。若系统 DNS 为空（极端情况），
     * 回落到国内常用公共 DNS（AliDNS 223.5.5.5、114DNS 114.114.114.114）。
     */
    private fun getSystemDnsServers(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ""
        val servers = linkedSetOf<String>()

        // 遍历所有网络，跳过 VPN 自身，只取底层 WiFi/蜂窝网络的 DNS
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val lp = cm.getLinkProperties(network) ?: continue
            lp.dnsServers.forEach { addr ->
                addr.hostAddress?.let { servers.add(it) }
            }
        }

        // 兜底：若底层网络 DNS 为空，试 activeNetwork（可能含 VPN，但好过没有）
        if (servers.isEmpty()) {
            cm.activeNetwork?.let { netId ->
                cm.getLinkProperties(netId)?.dnsServers?.forEach { addr ->
                    addr.hostAddress?.let { servers.add(it) }
                }
            }
        }

        // 最终兜底：国内常用公共 DNS。小米 17 在国内使用，AliDNS + 114DNS 覆盖主流场景。
        if (servers.isEmpty()) {
            addLog("No system DNS found, falling back to public DNS (223.5.5.5, 114.114.114.114)")
            servers.add("223.5.5.5")
            servers.add("114.114.114.114")
        }

        return servers.joinToString(",")
    }

    /**
     * 预解析 iroh 基础设施域名的 IP，注入 Rust 侧 OverrideResolver。
     *
     * GFW 会丢弃 iroh.link 域名的 UDP DNS 响应，导致 iroh 内部 hickory 解析
     * dns.iroh.link / *.relay.n0.iroh.link 超时。这里用系统 DNS（InetAddress，
     * 可能走 DoT/Private DNS 绕过 GFW）预解析这些域名的 IP，构造
     * "domain=ip1,ip2;domain2=ip3" 格式返回，供 connect() 传给 nativeSetDnsOverride。
     * OverrideResolver 对这些域名直接返回预解析 IP，使 pkarr resolve（HTTPS to
     * dns.iroh.link/pkarr/<z32>）和 relay 连接能成功。
     */
    private suspend fun resolveIrohDnsOverrides(): String {
        // iroh presets::N0 使用的 DNS origin + 默认 relay 服务器。
        val domains = listOf(
            "dns.iroh.link",
            "use1-1.relay.n0.iroh.link",
            "usw1-1.relay.n0.iroh.link",
            "euc1-1.relay.n0.iroh.link",
            "aps1-1.relay.n0.iroh.link",
        )

        val overrides = kotlinx.coroutines.coroutineScope {
            domains.map { domain ->
                async(Dispatchers.IO) {
                    try {
                        val addrs = InetAddress.getAllByName(domain)
                        val ips = addrs.mapNotNull { it.hostAddress }
                        if (ips.isNotEmpty()) {
                            addLog("Resolved iroh domain $domain -> $ips")
                            "$domain=${ips.joinToString(",")}"
                        } else {
                            addLog("Failed to resolve iroh domain $domain (no IPs)")
                            null
                        }
                    } catch (e: Exception) {
                        addLog("Failed to resolve iroh domain $domain: ${e.message}")
                        null
                    }
                }
            }.awaitAll()
        }

        val result = overrides.filterNotNull().joinToString(";")
        addLog("iroh DNS overrides: $result")
        return result
    }

    /**
     * 清空并重新添加 domain→node 映射。返回所有需代理的 domain 列表；
     * 若无任何映射则抛异常。
     */
    private suspend fun addDomainMappings(): List<String> {
        IrohProxy.nativeClearNodes()
        var hasDomainMappings = false
        for (node in nodes.value) {
            if (node.domains.isNotEmpty()) {
                for (domain in node.domains) {
                    addLog("Adding domain mapping: '$domain' -> nodeId: ${node.nodeId}")
                    val result = IrohProxy.nativeAddDomainMapping(domain, node.nodeId)
                    if (result != 0) {
                        addLog("Failed to add domain mapping: $domain")
                    } else {
                        hasDomainMappings = true
                    }
                }
            }
        }
        if (!hasDomainMappings) {
            throw Exception("No domain mappings configured. Please add nodes with domains.")
        }
        return nodes.value.flatMap { it.domains }
    }

    /**
     * 启动本地代理。先 nativeStopProxy（Rust 侧确定式回收监听端口，无需 delay），
     * 再按端口递增重试最多 10 次。返回实际监听端口。
     */
    private suspend fun startProxyWithRetries(basePort: Int): Int {
        addLog("Starting proxy...")
        IrohProxy.nativeStopProxy()
        var result = -1
        var actualPort = basePort
        for (attempt in 0..9) {
            actualPort = basePort + attempt
            addLog("Trying to start proxy on port $actualPort...")
            result = IrohProxy.nativeStartProxy(actualPort)
            if (result == 0) break
            addLog("Failed to start proxy on port $actualPort, retrying...")
            delay(200)
        }
        if (result != 0) {
            throw Exception("Failed to start proxy on ports $basePort..${basePort + 9}")
        }
        addLog("Proxy started on port $actualPort")
        return actualPort
    }

    /**
     * 全量释放隧道资源：nativeDestroy（停本地代理 + 释放 iroh endpoint）+ 复位状态 + 停 VPN 服务。
     * 关键点：复位 isIrohStarted=false，且 nativeDestroy 清掉 endpoint，使下次 connect
     * 重新执行 nativeStartIroh 建立全新隧道，而非复用旧 endpoint。
     * 注意：这里用 nativeDestroy 而非 nativeStopProxy——后者只停代理、保留 endpoint，
     * 供 startProxyWithRetries 重绑端口时使用。
     */
    private suspend fun releaseAllResources(context: Context) {
        try {
            IrohProxy.nativeDestroy()
        } catch (e: Exception) {
            addLog("releaseAllResources: nativeDestroy failed: ${e.message}")
        }
        isIrohStarted.value = false
        endpointId.value = ""
        try {
            val intent = Intent(context, NexaVpnService::class.java).apply {
                action = NexaVpnService.ACTION_STOP
            }
            context.startService(intent)
        } catch (_: Exception) {}
        isVpnRunning.value = false
    }

    fun connect(context: Context) {
        if (isConnecting.value) return
        connectJob = viewModelScope.launch(Dispatchers.IO) {
            // 与 disconnect 互斥；若 disconnect 正在进行则放弃本次连接。
            if (!connectionMutex.tryLock()) {
                addLog("connect: disconnect in progress, aborting")
                return@launch
            }
            try {
                isConnecting.value = true
                errorMessage.value = null
                connectionStatusText.value = null

                if (!IrohProxy.isNativeLoaded()) {
                    throw Exception("Native library not loaded. Please check if libnexapipe_client.so is properly included in the APK.")
                }
                val vpnPermissionGranted = VpnService.prepare(context) == null
                if (!vpnPermissionGranted) {
                    throw Exception("VPN permission not granted. Please grant VPN permission first.")
                }

                val basePort = LOCAL_PROXY_PORT
                var lastError: Exception? = null

                // 重试循环：每次尝试整体超时 ATTEMPT_TIMEOUT_MS；超时/失败后全量释放资源再重试。
                for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
                    try {
                        withTimeout(ATTEMPT_TIMEOUT_MS) {
                            // 注入系统 DNS 给 iroh，避免 iroh 在 Android 上 JNI 读系统 DNS
                            // 失败后回落 Google DNS（国内不稳定导致后端连不上）。
                            // 每次重试都重新获取，因为 releaseAllResources 后网络可能变化。
                            val dnsServers = getSystemDnsServers(context)
                            if (dnsServers.isNotEmpty()) {
                                addLog("Injecting system DNS servers: $dnsServers")
                                IrohProxy.nativeSetDnsServers(dnsServers)
                            }
                            // 预解析 iroh 基础设施域名（dns.iroh.link + relay），
                            // 绕过 GFW 对 iroh.link UDP DNS 响应的阻断。每次重试都重新解析。
                            val dnsOverrides = resolveIrohDnsOverrides()
                            if (dnsOverrides.isNotEmpty()) {
                                IrohProxy.nativeSetDnsOverride(dnsOverrides)
                            }
                            ensureIrohStarted()
                            val allDomains = addDomainMappings()
                            val port = startProxyWithRetries(basePort)

                            // Pre-connect：通过本地代理预热 iroh 连接。
                            connectionStatusText.value = "Pre-connecting..."
                            addLog("Pre-connecting to ${allDomains.size} domains...")
                            val preConnectedCount = preConnectAll(allDomains, port)
                            connectionStatusText.value = null
                            addLog("Pre-connect completed: $preConnectedCount/${allDomains.size} domains succeeded")
                            if (preConnectedCount > 0) {
                                addLog("Pre-connect succeeded for at least one domain")
                                delay(1000)
                            } else {
                                addLog("All pre-connect attempts failed, starting VPN anyway")
                            }
                        }

                        // 成功：拉起 VPN 前台服务。
                        val allDomains = nodes.value.flatMap { it.domains }
                        val intent = Intent(context, NexaVpnService::class.java).apply {
                            action = NexaVpnService.ACTION_START
                            putStringArrayListExtra(NexaVpnService.EXTRA_DOMAINS, ArrayList(allDomains))
                        }
                        context.startForegroundService(intent)
                        isVpnRunning.value = true
                        addLog("VPN connected successfully (attempt $attempt/$MAX_CONNECT_ATTEMPTS)")
                        return@launch
                    } catch (e: TimeoutCancellationException) {
                        // withTimeout 超时：可重试。
                        addLog("Attempt $attempt/$MAX_CONNECT_ATTEMPTS timed out after ${ATTEMPT_TIMEOUT_MS}ms")
                        lastError = e
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // disconnect 主动取消：不重试，向上传播以退出循环。
                        throw e
                    } catch (e: Exception) {
                        addLog("Attempt $attempt/$MAX_CONNECT_ATTEMPTS failed: ${e.message}")
                        lastError = e
                    }

                    if (attempt < MAX_CONNECT_ATTEMPTS) {
                        addLog("Releasing all resources before retry...")
                        releaseAllResources(context)
                        delay(BACKOFF_MS[attempt])
                    }
                }
                errorMessage.value = lastError?.message ?: "All connection attempts failed"
                addLog("All $MAX_CONNECT_ATTEMPTS attempts failed")
            } finally {
                isConnecting.value = false
                connectionStatusText.value = null
                connectionMutex.unlock()
            }
        }
    }

    /**
     * Send a lightweight HTTP GET request through the local proxy to warm up the iroh
     * connection. Returns true if the backend responded (even with an error code),
     * false on timeout or network failure.
     */
    private suspend fun preConnect(domain: String, proxyPort: Int, maxRetries: Int = 1): Boolean {
        for (attempt in 0 until maxRetries) {
            val success = try {
                withTimeout(6_000) {
                    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
                    val url = java.net.URL("http://$domain/")
                    val conn = url.openConnection(proxy) as HttpURLConnection
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = false
                    conn.responseCode
                    true
                }
            } catch (e: Exception) {
                if (attempt < maxRetries - 1) {
                    Log.w(TAG, "Pre-connect to $domain attempt ${attempt + 1} failed: ${e.message}, retrying...")
                    delay(500)
                } else {
                    Log.w(TAG, "Pre-connect to $domain failed after $maxRetries attempts: ${e.message}")
                }
                false
            }
            if (success) {
                return true
            }
        }
        return false
    }

    /**
     * Pre-connect to all domains in parallel to warm up iroh connections.
     * Returns the number of successfully pre-connected domains.
     * 注意：connectionStatusText 由 connect() 统一设置，此处不再重复赋值。
     */
    private suspend fun preConnectAll(domains: List<String>, proxyPort: Int): Int {
        addLog("Starting parallel pre-connect for ${domains.size} domains")

        return kotlinx.coroutines.coroutineScope {
            val deferredResults = domains.map { domain ->
                async {
                    addLog("Pre-connecting to $domain")
                    val success = preConnect(domain, proxyPort)
                    if (success) {
                        addLog("Pre-connect to $domain succeeded")
                    } else {
                        addLog("Pre-connect to $domain failed")
                    }
                    success
                }
            }

            deferredResults.awaitAll().count { it }
        }
    }

    fun disconnect(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            // 取消进行中的 connect：JNI 不可中断，但会在下一挂起点抛 CancellationException 退出。
            connectJob?.cancel()

            // 等待 connect 释放互斥锁（其 finally 会 unlock）。预算略大于单次连接超时。
            val locked = withTimeoutOrNull(DISCONNECT_MUTEX_TIMEOUT_MS) {
                connectionMutex.withLock {
                    releaseAllResources(context)
                }
            }
            if (locked == null) {
                // 极端情况：connect 仍卡在 JNI 超过预算。Rust 侧已无死锁，直接强制释放。
                addLog("disconnect: could not acquire mutex within ${DISCONNECT_MUTEX_TIMEOUT_MS}ms, forcing release")
                releaseAllResources(context)
            }
            addLog("VPN disconnected")
        }
    }

    fun checkVpnPermission(context: Context): Boolean {
        val granted = VpnService.prepare(context) == null
        vpnPermissionGranted.value = granted
        return granted
    }

    fun checkNotificationPermission(context: Context): Boolean {
        val granted = PermissionManager.checkNotificationPermission(context)
        notificationPermissionGranted.value = granted
        return granted
    }

    fun refreshPermissions(context: Context) {
        checkVpnPermission(context)
        checkNotificationPermission(context)
    }

    fun addNode(nodeId: String) {
        if (nodeId.isNotEmpty() && !nodes.value.any { it.nodeId == nodeId }) {
            nodes.value.add(NodeConfig(nodeId))
            nodes.value = nodes.value.toMutableList()
            saveSettings()
        }
    }

    fun removeNode(nodeId: String) {
        nodes.value.removeAll { it.nodeId == nodeId }
        nodes.value = nodes.value.toMutableList()
        saveSettings()
        if (isIrohStarted.value) {
            IrohProxy.nativeRemoveNode(nodeId)
            addLog("Removed node: $nodeId")
        }
    }

    fun addDomainToNode(nodeId: String, domain: String) {
        if (domain.isNotEmpty()) {
            val nodeIndex = nodes.value.indexOfFirst { it.nodeId == nodeId }
            if (nodeIndex != -1) {
                val node = nodes.value[nodeIndex]
                if (!node.domains.contains(domain)) {
                    val newDomains = node.domains.toMutableList().apply { add(domain) }
                    val newNode = node.copy(domains = newDomains)
                    val newNodes = nodes.value.toMutableList().apply { set(nodeIndex, newNode) }
                    nodes.value = newNodes
                    saveSettings()
                }
            }
        }
    }

    fun removeDomainFromNode(nodeId: String, domain: String) {
        val nodeIndex = nodes.value.indexOfFirst { it.nodeId == nodeId }
        if (nodeIndex != -1) {
            val node = nodes.value[nodeIndex]
            if (node.domains.contains(domain)) {
                val newDomains = node.domains.toMutableList().apply { remove(domain) }
                val newNode = node.copy(domains = newDomains)
                val newNodes = nodes.value.toMutableList().apply { set(nodeIndex, newNode) }
                nodes.value = newNodes
                saveSettings()
            }
        }
    }

    fun clearLogs() {
        logMessages.value.clear()
        logMessages.value = logMessages.value.toMutableList()
    }
}