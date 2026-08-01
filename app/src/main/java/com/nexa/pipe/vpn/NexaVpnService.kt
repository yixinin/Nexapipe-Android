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
import com.nexa.pipe.MainActivity
import com.nexa.pipe.R
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

class NexaVpnService : VpnService() {
    private val TAG = "NexaVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunOutputStream: FileOutputStream? = null
    private var isRunning = false
    private var proxyPort = 8080
    private var allowedDomains = mutableSetOf<String>()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var underlyingNetwork: Network? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isUserStarted = false
    private var proxyReaderJobs = mutableListOf<kotlinx.coroutines.Job>()
    private var tunWriterExecutor: ExecutorService? = null

    // 递增的 IP Identification，避免内核分片时 ID 冲突导致数据损坏
    private val ipIdCounter = java.util.concurrent.atomic.AtomicInteger(1)

    // TUN 子网配置
    private val virtualDNSIP = "10.0.1.2"
    private val tunInterfaceIP = "10.0.1.1"
    private val virtualProxyIP = "10.0.1.3"  // 虚拟代理 IP，位于 TUN 子网内

    // 建立 VPN 后的 stabilization 延迟（毫秒）
    private val VPN_STABILIZE_DELAY_MS = 1500L

    // DNS 劫持缓存
    private val domainToProxyIP = ConcurrentHashMap<String, String>()

    // ============================================================
    // TCP 连接状态管理
    // ============================================================

    private data class TcpConnection(
        val proxySocket: Socket,
        val proxyOutput: OutputStream,
        val clientIP: String,
        val clientPort: Int,
        val dstPort: Int,
        var clientSeq: Long,  // 客户端当前的序列号
        var serverSeq: Long,  // 服务端（我们）的序列号
        var clientAck: Long,  // 客户端期望收到的下一个序列号
        var serverAck: Long,  // 服务端期望收到的下一个序列号
        var readerJob: kotlinx.coroutines.Job? = null  // 关联的 reader job
    )

    // key = "clientIP:clientPort"
    private val tcpConnections = ConcurrentHashMap<String, TcpConnection>()

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

    fun startVPN(proxyPort: Int, domains: Set<String>) {
        this.proxyPort = proxyPort
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
                    val port = intent.getIntExtra(EXTRA_PROXY_PORT, 8080)
                    val domains = intent.getStringArrayListExtra(EXTRA_DOMAINS) ?: emptyList()
                    startVPN(port, domains.toSet())
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

        // 关闭所有 TCP 代理连接
        for ((_, conn) in tcpConnections) {
            try { conn.proxySocket.close() } catch (_: Exception) {}
        }
        tcpConnections.clear()
        proxyReaderJobs.forEach { it.cancel() }
        proxyReaderJobs.clear()

        try {
            tunOutputStream?.close()
        } catch (_: Exception) {}
        tunOutputStream = null

        vpnInterface?.close()
        vpnInterface = null

        tunWriterExecutor?.shutdown()
        tunWriterExecutor = null

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
                    // TCP 代理流量（到 10.0.1.3:80）走 TUN
                    .addRoute("10.0.1.0", 24)
                    .addDnsServer(virtualDNSIP)
                    // 排除自身 APP 流量，确保代理连接走物理网络
                    .addDisallowedApplication(packageName)
                    // 不设置 setBlocking(true) — 避免 iptables 拦截 loopback 和物理网络流量

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

                try {
                    tunOutputStream?.close()
                } catch (_: Exception) {}
                tunOutputStream = FileOutputStream(vpnInterface?.fileDescriptor)

                Log.d(TAG, "VPN established successfully (routing 10.0.1.0/24)")
                createNotificationChannel()
                startForeground(1, createNotification())

                Log.d(TAG, "Waiting ${VPN_STABILIZE_DELAY_MS}ms for network stabilization...")
                delay(VPN_STABILIZE_DELAY_MS)

                // 启动 TUN 读取（处理 DNS 和 TCP 代理）
                startPacketProcessing()
            } catch (e: Exception) {
                Log.d(TAG, "VPN establishment failed: ${e.message}")
                isRunning = false
            }
        }
    }

    // ============================================================
    // TUN 读取 — 处理 DNS (UDP 53) 和 TCP 代理
    // ============================================================

    private fun startPacketProcessing() {
        serviceScope.launch {
            val buffer = ByteArray(1500)
            var inputStream: FileInputStream? = null

            try {
                inputStream = FileInputStream(vpnInterface?.fileDescriptor)

                while (isRunning) {
                    val fd = vpnInterface?.fileDescriptor
                    if (fd == null || !fd.valid()) break

                    val length = inputStream.read(buffer)
                    if (length <= 0) {
                        delay(10)
                        continue
                    }

                    val packet = buffer.copyOf(length)
                    if (packet.size < 20) continue

                    val version = (packet[0].toInt() shr 4) and 0x0F
                    if (version != 4) continue

                    val ipHeaderLength = ((packet[0].toInt() and 0x0F) * 4)
                    val protocol = packet[9].toInt() and 0xFF

                    when (protocol) {
                        17 -> handleUDPPacket(packet, ipHeaderLength)   // UDP
                        6 -> handleTCPPacket(packet, ipHeaderLength)    // TCP
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.d(TAG, "Error processing packet: ${e.message}")
                }
            } finally {
                try {
                    inputStream?.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun writeToTun(data: ByteArray) {
        val writer = tunWriterExecutor ?: createTunWriterExecutor()
        try {
            writer.execute {
                try {
                    tunOutputStream?.write(data)
                    tunOutputStream?.flush()
                } catch (e: Exception) {
                    Log.d(TAG, "Error writing to TUN: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error scheduling TUN write: ${e.message}")
        }
    }

    private fun createTunWriterExecutor(): ExecutorService {
        tunWriterExecutor?.let { return it }
        return synchronized(this) {
            tunWriterExecutor ?: Executors.newSingleThreadExecutor { r ->
                Thread(r, "nexa-tun-writer").apply { isDaemon = true }
            }.also { tunWriterExecutor = it }
        }
    }

    // ============================================================
    // UDP / DNS 处理
    // ============================================================

    private fun handleUDPPacket(packet: ByteArray, ipHeaderLength: Int) {
        if (packet.size < ipHeaderLength + 8) return

        val dstPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 3].toInt() and 0xFF)

        // 只处理 DNS 查询（端口 53）
        if (dstPort != 53) return

        val udpHeaderLength = 8
        if (packet.size < ipHeaderLength + udpHeaderLength) return

        val dnsPayload = packet.copyOfRange(ipHeaderLength + udpHeaderLength, packet.size)
        val domain = extractDomainFromDNSQuery(dnsPayload)

        Log.d(TAG, "DNS query for: $domain")

        val shouldProxy = domain.isNotEmpty() && shouldProxyDomain(domain)
        Log.d(TAG, "Domain '$domain' shouldProxy: $shouldProxy, allowedDomains: $allowedDomains")

        val response = if (shouldProxy) {
            Log.d(TAG, "Proxying domain '$domain' to virtual IP $virtualProxyIP")
            createDNSResponse(dnsPayload, virtualProxyIP)
        } else {
            Log.d(TAG, "Querying real DNS for domain '$domain'")
            queryRealDNS(dnsPayload)
        }

        if (response == null) return

        // 提取请求方 IP 和端口
        val srcIP = extractIP(packet, 12)
        val srcPort = ((packet[ipHeaderLength].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 1].toInt() and 0xFF)

        val udpPacket = createUDPPacket(virtualDNSIP, 53, srcIP, srcPort, response)
        writeToTun(udpPacket)
    }

    private fun shouldProxyDomain(domain: String): Boolean {
        if (domain.endsWith(".iroh.link") || domain.endsWith(".n0.iroh.link")) {
            return false
        }

        if (allowedDomains.isEmpty()) return true
        for (allowedDomain in allowedDomains) {
            if (domain == allowedDomain || domain.endsWith(".$allowedDomain")) {
                return true
            }
        }
        return false
    }

    private fun extractDomainFromDNSQuery(dnsPayload: ByteArray): String {
        if (dnsPayload.size < 12) return ""

        val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
        if (qdCount == 0) return ""

        val sb = StringBuilder()
        var pos = 12

        while (pos < dnsPayload.size) {
            val len = dnsPayload[pos].toInt() and 0xFF
            if (len == 0) break

            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) {
                sb.append(dnsPayload[pos + 1 + i].toInt().toChar())
            }
            pos += len + 1
        }

        return sb.toString()
    }

    private fun createDNSResponse(request: ByteArray, ip: String): ByteArray {
        val response = ByteArray(request.size + 16)
        System.arraycopy(request, 0, response, 0, request.size)

        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()

        response[6] = 0x00.toByte()
        response[7] = 0x01.toByte()

        var pos = request.size
        response[pos++] = 0xC0.toByte()
        response[pos++] = 0x0C.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x01.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x01.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x3C.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x04.toByte()

        val ipParts = ip.split('.')
        for (part in ipParts) {
            response[pos++] = part.toInt().toByte()
        }

        return response.copyOf(pos)
    }

    private fun queryRealDNS(dnsPayload: ByteArray): ByteArray? {
        try {
            val domain = extractDomainFromDNSQuery(dnsPayload)
            if (domain.isEmpty()) {
                Log.d(TAG, "Empty domain in DNS query")
                return null
            }

            Log.d(TAG, "Resolving domain: $domain")

            val addresses = if (underlyingNetwork != null) {
                underlyingNetwork!!.getAllByName(domain)
            } else {
                InetAddress.getAllByName(domain)
            }

            if (addresses.isEmpty()) {
                Log.d(TAG, "No addresses found for $domain")
                return null
            }

            Log.d(TAG, "Resolved $domain to ${addresses[0].hostAddress}")
            return createDNSResponseForDomain(dnsPayload, addresses[0])
        } catch (e: Exception) {
            Log.d(TAG, "DNS resolution failed: ${e.message}")
            return null
        }
    }

    private fun createDNSResponseForDomain(request: ByteArray, address: InetAddress): ByteArray {
        val response = ByteArray(request.size + 16)
        System.arraycopy(request, 0, response, 0, request.size)

        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()

        response[6] = 0x00.toByte()
        response[7] = 0x01.toByte()

        var pos = request.size

        response[pos++] = 0xC0.toByte()
        response[pos++] = 0x0C.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x01.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x01.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x3C.toByte()
        response[pos++] = 0x00.toByte()
        response[pos++] = 0x04.toByte()

        val ipBytes = address.address
        System.arraycopy(ipBytes, 0, response, pos, 4)
        pos += 4

        return response.copyOf(pos)
    }

    // ============================================================
    // TCP 代理处理
    // ============================================================

    private fun handleTCPPacket(packet: ByteArray, ipHeaderLength: Int) {
        if (packet.size < ipHeaderLength + 20) return

        val tcpHeaderLen = ((packet[ipHeaderLength + 12].toInt() shr 4) and 0x0F) * 4
        if (packet.size < ipHeaderLength + tcpHeaderLen) return

        val dstIP = extractIP(packet, 16)
        val dstPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 3].toInt() and 0xFF)

        if (dstIP != virtualProxyIP) return

        // 处理到虚拟代理 IP 的 TCP 80 和 443 端口流量（HTTP 和 HTTPS）
        if (dstPort != 80 && dstPort != 443) return

        val srcIP = extractIP(packet, 12)
        val srcPort = ((packet[ipHeaderLength].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 1].toInt() and 0xFF)
        val flags = packet[ipHeaderLength + 13].toInt() and 0xFF
        val seqNum = readInt(packet, ipHeaderLength + 4)
        val ackNum = readInt(packet, ipHeaderLength + 8)

        val connKey = "$srcIP:$srcPort:$dstPort"

        when {
            // SYN (without ACK) — 三次握手第一步
            flags and 0x02 != 0 && flags and 0x10 == 0 -> {
                Log.d(TAG, "TCP SYN from $srcIP:$srcPort to $dstIP:$dstPort")
                handleTCPSYN(connKey, srcIP, srcPort, dstPort, seqNum)
            }
            // ACK (可能包含 PSH) — 数据或握手确认
            flags and 0x10 != 0 -> {
                val dataLen = packet.size - ipHeaderLength - tcpHeaderLen
                if (dataLen > 0 && (flags and 0x08 != 0)) {
                    Log.d(TAG, "TCP PSH+ACK from $srcIP:$srcPort ($dataLen bytes)")
                }
                handleTCPData(connKey, seqNum, ackNum, packet, ipHeaderLength, tcpHeaderLen, dataLen)
            }
            // FIN — 关闭连接
            flags and 0x01 != 0 -> {
                Log.d(TAG, "TCP FIN from $srcIP:$srcPort")
                handleTCPFIN(connKey)
            }
            // RST — 重置连接
            flags and 0x04 != 0 -> {
                Log.d(TAG, "TCP RST from $srcIP:$srcPort")
                handleTCPRST(connKey)
            }
        }
    }

    private fun handleTCPSYN(connKey: String, clientIP: String, clientPort: Int, dstPort: Int, clientSeq: Long) {
        // 如果已存在连接，取消旧 reader 并关闭旧 socket，避免新旧 reader 竞争同一个 connKey
        tcpConnections.remove(connKey)?.let { old ->
            old.readerJob?.cancel()
            try { old.proxySocket.close() } catch (_: Exception) {}
        }

        try {
            // 创建到本地代理的连接
            val proxySocket = Socket()
            proxySocket.connect(InetSocketAddress("127.0.0.1", proxyPort), 5000)
            Log.d(TAG, "TCP SYN: connected to proxy 127.0.0.1:$proxyPort, dstPort=$dstPort")

            val serverSeq = Random.nextLong(1_000_000_000, 2_000_000_000)

            val conn = TcpConnection(
                proxySocket = proxySocket,
                proxyOutput = proxySocket.getOutputStream(),
                clientIP = clientIP,
                clientPort = clientPort,
                dstPort = dstPort,
                clientSeq = clientSeq + 1,  // 收到 SYN 后，期望的下一个序列号
                serverSeq = serverSeq,
                clientAck = serverSeq + 1,  // 我们期望客户端确认我们的 SYN
                serverAck = clientSeq + 1   // 客户端期望我们确认它的 SYN
            )

            tcpConnections[connKey] = conn

            // 发送 SYN-ACK
            sendTCPPacket(
                srcIP = virtualProxyIP, srcPort = dstPort,
                dstIP = clientIP, dstPort = clientPort,
                seqNum = serverSeq, ackNum = clientSeq + 1,
                flags = 0x12,  // SYN + ACK
                data = null
            )

            // SYN 标志消耗一个序列号，更新 serverSeq
            val updatedConn = conn.copy(serverSeq = serverSeq + 1)
            tcpConnections[connKey] = updatedConn

            // 启动后台协程，读取代理 socket 数据并写入 TUN
            val readerJob = serviceScope.launch {
                var localServerSeq = updatedConn.serverSeq
                var localServerAck = updatedConn.serverAck
                var hasReceivedData = false
                var isWebSocket = false
                try {
                    // local proxy 收完数据后会主动关闭连接，这里只做兜底。
                    // 60s 覆盖 iroh 初始连接建立和慢速中继的传输间隔。
                    proxySocket.soTimeout = 60000
                    val buf = ByteArray(4096)
                    val inputStream = proxySocket.getInputStream()
                    while (isRunning && proxySocket.isConnected && !proxySocket.isClosed) {
                        val n = inputStream.read(buf)
                        if (n <= 0) break

                        hasReceivedData = true

                        val data = buf.copyOf(n)

                        val isWsResponse = !isWebSocket && isWebSocketResponse(data)
                        if (isWsResponse) {
                            isWebSocket = true
                            proxySocket.soTimeout = 300000
                            Log.d(TAG, "WebSocket connection detected, timeout extended to 300s")
                        }

                        val modifiedData = if (isWebSocket) data else replaceConnectionHeader(data)

                        val latestConn = tcpConnections[connKey]
                        val ackNum = latestConn?.serverAck ?: localServerAck
                        sendTCPPacket(
                            srcIP = virtualProxyIP, srcPort = dstPort,
                            dstIP = clientIP, dstPort = clientPort,
                            seqNum = localServerSeq, ackNum = ackNum,
                            flags = 0x18,  // PSH + ACK
                            data = modifiedData
                        )

                        localServerSeq += n
                        tcpConnections[connKey]?.let { latest ->
                            if (latest.proxySocket === proxySocket) {
                                tcpConnections[connKey] = latest.copy(serverSeq = localServerSeq)
                            }
                        }
                        Log.d(TAG, "TCP packet sent: $n bytes for $connKey, seq=${localServerSeq - n}->$localServerSeq")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Proxy reader ended for $connKey: ${e.message}")
                } finally {
                    Log.d(TAG, "Sending FIN for $connKey, hasReceivedData=$hasReceivedData, isWebSocket=$isWebSocket")
                    // 只有当前 socket 仍然关联这个 connKey 时才清理，避免误删新连接
                    val currentConn = tcpConnections[connKey]
                    if (currentConn != null && currentConn.proxySocket === proxySocket) {
                        sendTCPPacket(
                            srcIP = virtualProxyIP, srcPort = dstPort,
                            dstIP = clientIP, dstPort = clientPort,
                            seqNum = localServerSeq, ackNum = currentConn.serverAck,
                            flags = 0x11,  // FIN + ACK
                            data = null
                        )
                        Log.d(TAG, "FIN sent for $connKey, seq=$localServerSeq")
                        tcpConnections.remove(connKey)
                    }
                    try { proxySocket.close() } catch (_: Exception) {}
                }
            }
            proxyReaderJobs.add(readerJob)
            tcpConnections[connKey] = updatedConn.copy(readerJob = readerJob)

        } catch (e: Exception) {
            Log.d(TAG, "TCP SYN failed for $connKey: ${e.message}")
            tcpConnections.remove(connKey)

            // 发送 RST
            sendTCPPacket(
                srcIP = virtualProxyIP, srcPort = dstPort,
                dstIP = clientIP, dstPort = clientPort,
                seqNum = 0, ackNum = 0,
                flags = 0x14,  // RST + ACK
                data = null
            )
        }
    }

    private fun handleTCPData(
        connKey: String, seqNum: Long, ackNum: Long,
        packet: ByteArray, ipHeaderLength: Int, tcpHeaderLen: Int, dataLen: Int
    ) {
        val conn = tcpConnections[connKey] ?: return

        if (dataLen > 0 && seqNum < conn.clientSeq) {
            val latestConn = tcpConnections[connKey]
            sendTCPPacket(
                srcIP = virtualProxyIP, srcPort = conn.dstPort,
                dstIP = conn.clientIP, dstPort = conn.clientPort,
                seqNum = latestConn?.clientAck ?: conn.clientAck,
                ackNum = latestConn?.serverAck ?: conn.serverAck,
                flags = 0x10,  // ACK
                data = null
            )
            return
        }

        // 更新 ACK 号（客户端期望我们发送的下一个序列号）
        val updatedConn = if (dataLen > 0) {
            conn.copy(
                clientSeq = seqNum + dataLen,
                serverAck = seqNum + dataLen,
                clientAck = ackNum
            )
        } else {
            conn.copy(clientAck = ackNum)
        }
        tcpConnections[connKey] = updatedConn

        if (dataLen > 0) {
            try {
                val data = packet.copyOfRange(ipHeaderLength + tcpHeaderLen, packet.size)
                sendTCPPacket(
                    srcIP = virtualProxyIP, srcPort = updatedConn.dstPort,
                    dstIP = updatedConn.clientIP, dstPort = updatedConn.clientPort,
                    seqNum = updatedConn.clientAck, ackNum = updatedConn.serverAck,
                    flags = 0x10,  // ACK
                    data = null
                )
                updatedConn.proxyOutput.write(data)
                updatedConn.proxyOutput.flush()
            } catch (e: Exception) {
                Log.d(TAG, "TCP data write failed for $connKey: ${e.message}, attempting reconnection")
                val reconnected = tryReconnectProxy(connKey, updatedConn)
                if (reconnected) {
                    val newConn = tcpConnections[connKey] ?: return
                    try {
                        val data = packet.copyOfRange(ipHeaderLength + tcpHeaderLen, packet.size)
                        newConn.proxyOutput.write(data)
                        newConn.proxyOutput.flush()

                        sendTCPPacket(
                            srcIP = virtualProxyIP, srcPort = newConn.dstPort,
                            dstIP = newConn.clientIP, dstPort = newConn.clientPort,
                            seqNum = newConn.clientAck, ackNum = newConn.serverAck,
                            flags = 0x10,  // ACK
                            data = null
                        )
                    } catch (re: Exception) {
                        Log.d(TAG, "Reconnection write failed for $connKey: ${re.message}")
                        handleTCPRST(connKey)
                    }
                } else {
                    handleTCPRST(connKey)
                }
            }
        }
    }

    private fun handleTCPFIN(connKey: String) {
        tcpConnections.remove(connKey)?.let { conn ->
            conn.readerJob?.cancel()
            try { conn.proxySocket.close() } catch (_: Exception) {}
        }
    }

    private fun tryReconnectProxy(connKey: String, oldConn: TcpConnection): Boolean {
        try {
            Log.d(TAG, "Attempting proxy reconnection for $connKey")
            oldConn.readerJob?.cancel()
            try { oldConn.proxySocket.close() } catch (_: Exception) {}

            val newProxySocket = Socket()
            newProxySocket.connect(InetSocketAddress("127.0.0.1", proxyPort), 5000)
            Log.d(TAG, "Proxy reconnection successful for $connKey")

            val newConn = TcpConnection(
                proxySocket = newProxySocket,
                proxyOutput = newProxySocket.getOutputStream(),
                clientIP = oldConn.clientIP,
                clientPort = oldConn.clientPort,
                dstPort = oldConn.dstPort,
                clientSeq = oldConn.clientSeq,
                serverSeq = oldConn.serverSeq,
                clientAck = oldConn.clientAck,
                serverAck = oldConn.serverAck
            )

            val readerJob = serviceScope.launch {
                var localServerSeq = newConn.serverSeq
                var localServerAck = newConn.serverAck
                var hasReceivedData = false
                var isWebSocket = false
                try {
                    newProxySocket.soTimeout = 60000
                    val buf = ByteArray(4096)
                    val inputStream = newProxySocket.getInputStream()
                    while (isRunning && newProxySocket.isConnected && !newProxySocket.isClosed) {
                        val n = inputStream.read(buf)
                        if (n <= 0) break

                        hasReceivedData = true
                        val data = buf.copyOf(n)

                        if (!isWebSocket && isWebSocketResponse(data)) {
                            isWebSocket = true
                            newProxySocket.soTimeout = 300000
                            Log.d(TAG, "WebSocket connection detected (reconnected), timeout extended to 300s")
                        }

                        val modifiedData = if (isWebSocket) data else replaceConnectionHeader(data)

                        val latestConn = tcpConnections[connKey]
                        val ackNum = latestConn?.serverAck ?: localServerAck
                        sendTCPPacket(
                            srcIP = virtualProxyIP, srcPort = newConn.dstPort,
                            dstIP = newConn.clientIP, dstPort = newConn.clientPort,
                            seqNum = localServerSeq, ackNum = ackNum,
                            flags = 0x18,  // PSH + ACK
                            data = modifiedData
                        )

                        localServerSeq += n
                        tcpConnections[connKey]?.let { latest ->
                            if (latest.proxySocket === newProxySocket) {
                                tcpConnections[connKey] = latest.copy(serverSeq = localServerSeq)
                            }
                        }
                        Log.d(TAG, "TCP packet sent (reconnected): $n bytes for $connKey")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Proxy reader ended (reconnected) for $connKey: ${e.message}")
                } finally {
                    val currentConn = tcpConnections[connKey]
                    if (currentConn != null && currentConn.proxySocket === newProxySocket) {
                        sendTCPPacket(
                            srcIP = virtualProxyIP, srcPort = newConn.dstPort,
                            dstIP = newConn.clientIP, dstPort = newConn.clientPort,
                            seqNum = localServerSeq, ackNum = currentConn.serverAck,
                            flags = 0x11,  // FIN + ACK
                            data = null
                        )
                        tcpConnections.remove(connKey)
                    }
                    try { newProxySocket.close() } catch (_: Exception) {}
                }
            }
            proxyReaderJobs.add(readerJob)
            tcpConnections[connKey] = newConn.copy(readerJob = readerJob)
            return true
        } catch (e: Exception) {
            Log.d(TAG, "Proxy reconnection failed for $connKey: ${e.message}")
            return false
        }
    }

    private fun handleTCPRST(connKey: String) {
        tcpConnections.remove(connKey)?.let { conn ->
            conn.readerJob?.cancel()
            try { conn.proxySocket.close() } catch (_: Exception) {}
        }
    }

    // ============================================================
    // TCP 包构造
    // ============================================================

    private fun sendTCPPacket(
        srcIP: String, srcPort: Int,
        dstIP: String, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flags: Int,
        data: ByteArray?
    ) {
        val dataLen = data?.size ?: 0
        val tcpHeaderLen = 20  // 无选项
        val tcpSegmentLen = tcpHeaderLen + dataLen
        val ipTotalLen = 20 + tcpSegmentLen

        val packet = ByteArray(ipTotalLen)

        // IP 头部 (20 bytes, no options)
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((ipTotalLen shr 8) and 0xFF).toByte()
        packet[3] = (ipTotalLen and 0xFF).toByte()
        val ipId = ipIdCounter.getAndIncrement() and 0xFFFF
        packet[4] = ((ipId shr 8) and 0xFF).toByte()
        packet[5] = (ipId and 0xFF).toByte()
        packet[6] = 0x00.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 0x40.toByte()  // TTL
        packet[9] = 0x06.toByte()  // TCP protocol

        // IP 校验和暂填 0
        packet[10] = 0x00.toByte()
        packet[11] = 0x00.toByte()

        val srcIPBytes = ipToBytes(srcIP)
        val dstIPBytes = ipToBytes(dstIP)
        System.arraycopy(srcIPBytes, 0, packet, 12, 4)
        System.arraycopy(dstIPBytes, 0, packet, 16, 4)

        // TCP 头部 (20 bytes, no options)
        val tcpOffset = 20  // IP header length

        // Source port
        packet[tcpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 1] = (srcPort and 0xFF).toByte()
        // Dest port
        packet[tcpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 3] = (dstPort and 0xFF).toByte()

        // Sequence number
        writeInt(packet, tcpOffset + 4, seqNum)
        // Acknowledgment number
        writeInt(packet, tcpOffset + 8, ackNum)

        // Data offset (5 = 20 bytes) + flags
        packet[tcpOffset + 12] = (0x50).toByte()  // data offset = 5 (20 bytes)
        // Reserved (3 bits) + flags (9 bits)
        // flags: FIN=0x01, SYN=0x02, RST=0x04, PSH=0x08, ACK=0x10
        packet[tcpOffset + 13] = (flags and 0x3F).toByte()

        // Window size
        packet[tcpOffset + 14] = 0x10.toByte()
        packet[tcpOffset + 15] = 0x00.toByte()

        // Checksum (暂填 0)
        packet[tcpOffset + 16] = 0x00.toByte()
        packet[tcpOffset + 17] = 0x00.toByte()

        // Urgent pointer
        packet[tcpOffset + 18] = 0x00.toByte()
        packet[tcpOffset + 19] = 0x00.toByte()

        // 数据
        if (data != null && data.isNotEmpty()) {
            System.arraycopy(data, 0, packet, tcpOffset + tcpHeaderLen, data.size)
        }

        // 计算 TCP 校验和（使用伪首部）
        val pseudoHeaderLen = 12
        val checksumDataLen = pseudoHeaderLen + tcpSegmentLen
        val checksumData = ByteArray(checksumDataLen)

        System.arraycopy(srcIPBytes, 0, checksumData, 0, 4)
        System.arraycopy(dstIPBytes, 0, checksumData, 4, 4)
        checksumData[8] = 0x00.toByte()
        checksumData[9] = 0x06.toByte()  // TCP protocol
        checksumData[10] = ((tcpSegmentLen shr 8) and 0xFF).toByte()
        checksumData[11] = (tcpSegmentLen and 0xFF).toByte()

        System.arraycopy(packet, tcpOffset, checksumData, 12, tcpSegmentLen)

        val tcpChecksum = calculateChecksum(checksumData, 0, checksumDataLen)
        packet[tcpOffset + 16] = ((tcpChecksum shr 8) and 0xFF).toByte()
        packet[tcpOffset + 17] = (tcpChecksum and 0xFF).toByte()

        // 计算 IP 校验和
        val ipChecksum = calculateChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        writeToTun(packet)
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private fun extractIP(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}." +
                "${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"
    }

    private fun readInt(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }

    private fun writeInt(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    private fun createUDPPacket(srcIP: String, srcPort: Int, dstIP: String, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val ipLength = 20 + udpLength

        val packet = ByteArray(ipLength)

        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((ipLength shr 8) and 0xFF).toByte()
        packet[3] = (ipLength and 0xFF).toByte()
        val ipId = ipIdCounter.getAndIncrement() and 0xFFFF
        packet[4] = ((ipId shr 8) and 0xFF).toByte()
        packet[5] = (ipId and 0xFF).toByte()
        packet[6] = 0x00.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 0x40.toByte()
        packet[9] = 0x11.toByte()
        packet[10] = 0x00.toByte()
        packet[11] = 0x00.toByte()

        val srcIPBytes = ipToBytes(srcIP)
        val dstIPBytes = ipToBytes(dstIP)
        System.arraycopy(srcIPBytes, 0, packet, 12, 4)
        System.arraycopy(dstIPBytes, 0, packet, 16, 4)

        packet[20] = ((srcPort shr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = ((udpLength shr 8) and 0xFF).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        packet[26] = 0x00.toByte()
        packet[27] = 0x00.toByte()

        System.arraycopy(payload, 0, packet, 28, payload.size)

        // IP 校验和
        val ipChecksum = calculateChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP 伪首部校验和
        val pseudoHeader = ByteArray(12)
        System.arraycopy(srcIPBytes, 0, pseudoHeader, 0, 4)
        System.arraycopy(dstIPBytes, 0, pseudoHeader, 4, 4)
        pseudoHeader[8] = 0x00.toByte()
        pseudoHeader[9] = 0x11.toByte()
        pseudoHeader[10] = ((udpLength shr 8) and 0xFF).toByte()
        pseudoHeader[11] = (udpLength and 0xFF).toByte()

        val udpChecksumData = ByteArray(pseudoHeader.size + udpLength)
        System.arraycopy(pseudoHeader, 0, udpChecksumData, 0, 12)
        System.arraycopy(packet, 20, udpChecksumData, 12, udpLength)

        val udpChecksum = calculateChecksum(udpChecksumData, 0, udpChecksumData.size)
        packet[26] = ((udpChecksum shr 8) and 0xFF).toByte()
        packet[27] = (udpChecksum and 0xFF).toByte()

        return packet
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }

        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return sum.inv() and 0xFFFF
    }

    private fun ipToBytes(ip: String): ByteArray {
        val parts = ip.split('.')
        return byteArrayOf(
            parts[0].toInt().toByte(),
            parts[1].toInt().toByte(),
            parts[2].toInt().toByte(),
            parts[3].toInt().toByte()
        )
    }

    private fun replaceConnectionHeader(data: ByteArray): ByteArray {
        val keepAlive = "connection: keep-alive".toByteArray(Charsets.US_ASCII)
        val close = "connection: close".toByteArray(Charsets.US_ASCII)
        val dataStr = String(data, Charsets.US_ASCII).lowercase()

        val index = dataStr.indexOf("connection: keep-alive")
        if (index >= 0) {
            val result = data.copyOf()
            System.arraycopy(close, 0, result, index, close.size)
            for (i in close.size until keepAlive.size) {
                result[index + i] = ' '.code.toByte()
            }
            return result
        }
        return data
    }

    private fun isWebSocketResponse(data: ByteArray): Boolean {
        val dataStr = String(data, Charsets.US_ASCII).lowercase()
        return dataStr.contains("101 switching protocols")
    }

    // ============================================================
    // 网络回调
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

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
        const val EXTRA_PROXY_PORT = "com.nexa.pipe.vpn.EXTRA_PROXY_PORT"
        const val EXTRA_DOMAINS = "com.nexa.pipe.vpn.EXTRA_DOMAINS"
    }
}
