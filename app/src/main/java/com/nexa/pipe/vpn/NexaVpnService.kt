package com.nexa.pipe.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nexa.pipe.MainActivity
import com.nexa.pipe.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NexaVpnService : VpnService() {
    private val TAG = "NexaVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var proxyPort = 8080
    private var blockedDomains = mutableSetOf<String>()
    private var allowedDomains = mutableSetOf<String>()
    private val domainVirtualIpMap = mutableMapOf<String, String>()
    private val virtualIpDomainMap = mutableMapOf<String, String>()
    private var tcpConnections = mutableMapOf<String, TcpConnectionState>()

    private data class TcpConnectionState(
        val socket: Socket,
        val clientIp: String,
        val clientPort: Int,
        val virtualIp: String,
        val serverPort: Int,
        val domain: String,
        var clientSeq: Long,
        var serverSeq: Long,
        var clientAck: Long,
        var serverAck: Long
    )
    private var nextVirtualIp = 1
    private val VIRTUAL_IP_PREFIX = "10.0.1."
    private val VIRTUAL_DNS_IP = "10.0.1.1"
    private var cachedDnsServers = listOf<String>()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var isUserStarted = false
    private var isVpnEstablished = false
    private var isReconnectPending = false
    private var firstNetworkCallback = true
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 10

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VpnService created")
        createNotificationChannel()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkCallback = createNetworkCallback()
        connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_START -> {
                    proxyPort = intent.getIntExtra(EXTRA_PROXY_PORT, 8080)
                    val domains = intent.getStringArrayListExtra(EXTRA_DOMAINS)
                    domains?.let { allowedDomains.addAll(it) }
                    reconnectAttempts = 0
                    reconnectJob?.cancel()
                    cachedDnsServers = getSystemDnsServers()
                    Log.d(TAG, "Cached DNS servers before VPN: $cachedDnsServers")
                    startVpn()
                }
                ACTION_STOP -> {
                    stopVpn()
                }
            }
        }
        return START_STICKY
    }

    private fun startVpn(isReconnect: Boolean = false) {
        isUserStarted = true
        try {
            // Check if TUN device is available on the system
            val tunDevice = java.io.File("/dev/net/tun")
            if (!tunDevice.exists()) {
                Log.e(TAG, "TUN device not found at /dev/net/tun - system may not support VPN")
                throw IllegalStateException("TUN device not available on this device")
            }
            Log.d(TAG, "TUN device found: ${tunDevice.absolutePath}")

            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                Log.e(TAG, "VPN permission not granted, cannot establish VPN")
                throw IllegalStateException("VPN permission not granted")
            }

            val builder = Builder()
                .setMtu(1280)
                .addAddress("10.0.1.2", 24)
                .addRoute("10.0.1.0", 24)
                .addDnsServer(VIRTUAL_DNS_IP)
                .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", proxyPort))

            if (allowedDomains.isNotEmpty()) {
                Log.d(TAG, "Configured domains: $allowedDomains")
            }

            Log.d(TAG, "Attempting to establish VPN interface...")
            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface: builder.establish() returned null")
                throw IllegalStateException("Failed to establish VPN interface - system may not support TUN devices")
            }

            Log.d(TAG, "VPN interface established successfully, starting foreground service...")

            // Start foreground AFTER establishing VPN interface successfully
            isRunning = true
            isVpnEstablished = true
            isReconnectPending = false
            reconnectAttempts = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            Log.d(TAG, "Foreground service started, proxyPort: $proxyPort")

            startPacketProcessing()

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting VPN: ${e.message}", e)
            handleStartFailure(isReconnect)
            throw e
        } catch (e: IllegalStateException) {
            Log.e(TAG, "VPN startup failed: ${e.message}", e)
            handleStartFailure(isReconnect)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}", e)
            handleStartFailure(isReconnect)
            throw e
        }
    }

    private fun handleStartFailure(isReconnect: Boolean) {
        isVpnEstablished = false
        if (isReconnect) {
            Log.w(TAG, "VPN reconnect attempt failed, will retry")
            scheduleReconnect()
        } else {
            cleanupAndStop()
        }
    }
    
    private fun cleanupAndStop() {
        Log.d(TAG, "Cleaning up after VPN startup failure")
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping foreground service: ${e.message}")
        }
        stopSelf()
    }

    private fun stopVpn() {
        isUserStarted = false
        isVpnEstablished = false
        isReconnectPending = false
        reconnectAttempts = 0
        reconnectJob?.cancel()
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        closeAllTcpConnections()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        Log.d(TAG, "VPN stopped")
    }

    private fun stopVpnInterfaceOnly() {
        Log.d(TAG, "Stopping VPN interface only for reconnect")
        isVpnEstablished = false
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        closeAllTcpConnections()
    }

    private fun closeAllTcpConnections() {
        val keys = tcpConnections.keys.toList()
        for (key in keys) {
            closeTcpConnection(key)
        }
        tcpConnections.clear()
    }

    private fun createNetworkCallback(): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // The first callback fires immediately after registration with the current network;
                // ignore it to avoid an unnecessary restart right after the user connects.
                if (firstNetworkCallback) {
                    firstNetworkCallback = false
                    return
                }
                if (isUserStarted && isReconnectPending) {
                    Log.d(TAG, "Network available, scheduling VPN reconnect on $network")
                    scheduleReconnect()
                }
            }

            override fun onLost(network: Network) {
                if (!isUserStarted || !isVpnEstablished) return
                Log.d(TAG, "Network lost, tearing down VPN interface for reconnect")
                isReconnectPending = true
                reconnectJob?.cancel()
                serviceScope.launch(Dispatchers.IO) {
                    stopVpnInterfaceOnly()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch(Dispatchers.IO) {
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.e(TAG, "Max reconnect attempts reached, giving up")
                stopVpn()
                return@launch
            }
            val delayMs = (1500L * (1 shl reconnectAttempts)).coerceAtMost(30000L)
            Log.d(TAG, "Scheduling VPN reconnect in ${delayMs}ms (attempt ${reconnectAttempts + 1}/$MAX_RECONNECT_ATTEMPTS)")
            delay(delayMs)
            if (!isActive || !isUserStarted || isVpnEstablished) {
                return@launch
            }
            try {
                reconnectAttempts++
                cachedDnsServers = getSystemDnsServers()
                startVpn(isReconnect = true)
            } catch (e: Exception) {
                Log.e(TAG, "Auto-reconnect failed: ${e.message}", e)
            }
        }
    }

    private fun startPacketProcessing() {
        CoroutineScope(Dispatchers.IO).launch {
            val inputStream = FileInputStream(vpnInterface?.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface?.fileDescriptor)
            val buffer = ByteArray(1500)

            while (isRunning) {
                try {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        val packet = buffer.copyOf(length)
                        processPacket(packet, outputStream)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading packet", e)
                    if (!isRunning) break
                }
            }

            inputStream.close()
            outputStream.close()
        }
    }

    private fun processPacket(packet: ByteArray, outputStream: FileOutputStream) {
        try {
            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
            val protocol = packet[9].toInt()
            val sourceIp = byteArrayOf(packet[12], packet[13], packet[14], packet[15])
            val destIp = byteArrayOf(packet[16], packet[17], packet[18], packet[19])
            val sourceIpStr = ipToString(sourceIp)
            val destIpStr = ipToString(destIp)

            Log.d(TAG, "Packet: protocol=$protocol, src=$sourceIpStr, dst=$destIpStr, size=${packet.size}")

            if (protocol == 6) {
                processTcpPacket(packet, ipHeaderLength, outputStream)
            } else if (protocol == 17) {
                processUdpPacket(packet, ipHeaderLength, outputStream)
            } else {
                sendPacket(packet, outputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet", e)
        }
    }



    private fun processTcpPacket(packet: ByteArray, ipHeaderLength: Int, outputStream: FileOutputStream) {
        val tcpHeaderLength = ((packet[ipHeaderLength + 12].toInt() and 0xF0) shr 4) * 4
        val sourceIp = byteArrayOf(packet[12], packet[13], packet[14], packet[15])
        val sourceIpStr = ipToString(sourceIp)
        val destIp = byteArrayOf(packet[16], packet[17], packet[18], packet[19])
        val destIpStr = ipToString(destIp)
        val sourcePort = ((packet[ipHeaderLength].toInt() and 0xFF) shl 8) or (packet[ipHeaderLength + 1].toInt() and 0xFF)
        val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or (packet[ipHeaderLength + 3].toInt() and 0xFF)
        val flags = packet[ipHeaderLength + 13].toInt()
        val isSyn = (flags and 0x02) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val connectionKey = "$sourceIpStr:$sourcePort->$destIpStr:$destPort"

        Log.d(TAG, "TCP packet: $sourceIpStr:$sourcePort -> $destIpStr:$destPort (SYN=$isSyn, FIN=$isFin, RST=$isRst)")

        if (isRst || isFin) {
            closeTcpConnection(connectionKey)
            return
        }

        if (!isVirtualIp(destIpStr)) {
            Log.v(TAG, "Ignoring non-virtual IP traffic: $destIpStr:$destPort")
            return
        }

        val domain = virtualIpDomainMap[destIpStr]
        if (domain == null) {
            Log.w(TAG, "No domain mapping for virtual IP: $destIpStr")
            return
        }

        if (isSyn) {
            handleTcpSyn(packet, ipHeaderLength, tcpHeaderLength, connectionKey, sourceIpStr, sourcePort, destIpStr, destPort, domain, outputStream)
            return
        }

        val state = tcpConnections[connectionKey]
        if (state == null) {
            Log.w(TAG, "No TCP state for $connectionKey, dropping packet")
            return
        }

        val payloadOffset = ipHeaderLength + tcpHeaderLength
        if (payloadOffset < packet.size) {
            val payload = packet.copyOfRange(payloadOffset, packet.size)
            val seq = readTcpSeq(packet, ipHeaderLength)
            val ack = readTcpAck(packet, ipHeaderLength)
            state.clientSeq = seq
            state.clientAck = ack
            forwardToProxy(state, payload, outputStream)
        }
    }

    private fun handleTcpSyn(
        packet: ByteArray,
        ipHeaderLength: Int,
        tcpHeaderLength: Int,
        connectionKey: String,
        clientIp: String,
        clientPort: Int,
        virtualIp: String,
        serverPort: Int,
        domain: String,
        outputStream: FileOutputStream
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Handling SYN for $domain:$serverPort from $clientIp:$clientPort")

                val clientSeq = readTcpSeq(packet, ipHeaderLength)
                val proxySocket = Socket("127.0.0.1", proxyPort)
                proxySocket.soTimeout = 10000
                sendHttpConnect(proxySocket, domain, serverPort)

                val state = TcpConnectionState(
                    socket = proxySocket,
                    clientIp = clientIp,
                    clientPort = clientPort,
                    virtualIp = virtualIp,
                    serverPort = serverPort,
                    domain = domain,
                    clientSeq = clientSeq,
                    serverSeq = 1L,
                    clientAck = clientSeq + 1,
                    serverAck = clientSeq + 1
                )
                tcpConnections[connectionKey] = state

                startResponseReader(connectionKey, outputStream)

                val synAck = buildTcpPacket(
                    srcIp = virtualIp, srcPort = serverPort,
                    destIp = clientIp, destPort = clientPort,
                    seq = state.serverSeq, ack = state.clientAck,
                    flags = 0x12,
                    payload = ByteArray(0)
                )
                outputStream.write(synAck)
                outputStream.flush()
                state.serverSeq++

                Log.d(TAG, "SYN-ACK sent to $clientIp:$clientPort for $domain")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling SYN for $domain:$serverPort", e)
                sendRstPacket(packet, ipHeaderLength, tcpHeaderLength, outputStream)
            }
        }
    }

    private fun forwardToProxy(state: TcpConnectionState, payload: ByteArray, outputStream: FileOutputStream) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                state.socket.getOutputStream().write(payload)
                state.socket.getOutputStream().flush()
                state.serverAck += payload.size

                val ackPacket = buildTcpPacket(
                    srcIp = state.virtualIp, srcPort = state.serverPort,
                    destIp = state.clientIp, destPort = state.clientPort,
                    seq = state.serverSeq, ack = state.serverAck,
                    flags = 0x10,
                    payload = ByteArray(0)
                )
                outputStream.write(ackPacket)
                outputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding to proxy for ${state.domain}", e)
                closeTcpConnectionByState(state)
            }
        }
    }

    private fun closeTcpConnection(connectionKey: String) {
        tcpConnections.remove(connectionKey)?.let { state ->
            try { state.socket.close() } catch (_: Exception) {}
        }
    }

    private fun closeTcpConnectionByState(state: TcpConnectionState) {
        val key = "${state.clientIp}:${state.clientPort}->${state.virtualIp}:${state.serverPort}"
        tcpConnections.remove(key)
        try { state.socket.close() } catch (_: Exception) {}
    }

    private fun readTcpSeq(packet: ByteArray, ipHeaderLength: Int): Long {
        return ((packet[ipHeaderLength + 4].toInt() and 0xFF).toLong() shl 24) or
                ((packet[ipHeaderLength + 5].toInt() and 0xFF).toLong() shl 16) or
                ((packet[ipHeaderLength + 6].toInt() and 0xFF).toLong() shl 8) or
                (packet[ipHeaderLength + 7].toInt() and 0xFF).toLong()
    }

    private fun readTcpAck(packet: ByteArray, ipHeaderLength: Int): Long {
        return ((packet[ipHeaderLength + 8].toInt() and 0xFF).toLong() shl 24) or
                ((packet[ipHeaderLength + 9].toInt() and 0xFF).toLong() shl 16) or
                ((packet[ipHeaderLength + 10].toInt() and 0xFF).toLong() shl 8) or
                (packet[ipHeaderLength + 11].toInt() and 0xFF).toLong()
    }

    private fun buildTcpPacket(
        srcIp: String, srcPort: Int,
        destIp: String, destPort: Int,
        seq: Long, ack: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLength = 20
        val tcpHeaderLength = 20
        val totalLength = ipHeaderLength + tcpHeaderLength + payload.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = (totalLength shr 8).toByte()
        packet[3] = totalLength.toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 0xFF.toByte()
        packet[9] = 0x06.toByte()
        packet[10] = 0x00.toByte()
        packet[11] = 0x00.toByte()

        val srcIpBytes = srcIp.split(".").map { it.toInt().toByte() }.toByteArray()
        val destIpBytes = destIp.split(".").map { it.toInt().toByte() }.toByteArray()
        srcIpBytes.copyInto(packet, 12)
        destIpBytes.copyInto(packet, 16)

        packet[ipHeaderLength] = (srcPort shr 8).toByte()
        packet[ipHeaderLength + 1] = srcPort.toByte()
        packet[ipHeaderLength + 2] = (destPort shr 8).toByte()
        packet[ipHeaderLength + 3] = destPort.toByte()
        packet[ipHeaderLength + 4] = (seq shr 24).toByte()
        packet[ipHeaderLength + 5] = (seq shr 16).toByte()
        packet[ipHeaderLength + 6] = (seq shr 8).toByte()
        packet[ipHeaderLength + 7] = seq.toByte()
        packet[ipHeaderLength + 8] = (ack shr 24).toByte()
        packet[ipHeaderLength + 9] = (ack shr 16).toByte()
        packet[ipHeaderLength + 10] = (ack shr 8).toByte()
        packet[ipHeaderLength + 11] = ack.toByte()
        packet[ipHeaderLength + 12] = 0x50.toByte()
        packet[ipHeaderLength + 13] = flags.toByte()
        packet[ipHeaderLength + 14] = (65535 shr 8).toByte()
        packet[ipHeaderLength + 15] = 65535.toByte()
        packet[ipHeaderLength + 16] = 0x00.toByte()
        packet[ipHeaderLength + 17] = 0x00.toByte()
        packet[ipHeaderLength + 18] = 0x00.toByte()
        packet[ipHeaderLength + 19] = 0x00.toByte()

        payload.copyInto(packet, ipHeaderLength + tcpHeaderLength)

        val ipChecksum = calculateChecksum(packet, 0, ipHeaderLength)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = ipChecksum.toByte()

        val tcpChecksum = calculateTcpChecksum(packet, ipHeaderLength, tcpHeaderLength, payload.size)
        packet[ipHeaderLength + 16] = (tcpChecksum shr 8).toByte()
        packet[ipHeaderLength + 17] = tcpChecksum.toByte()

        return packet
    }

    private fun sendRstPacket(packet: ByteArray, ipHeaderLength: Int, tcpHeaderLength: Int, outputStream: FileOutputStream) {
        try {
            val srcIp = ipToString(byteArrayOf(packet[12], packet[13], packet[14], packet[15]))
            val destIp = ipToString(byteArrayOf(packet[16], packet[17], packet[18], packet[19]))
            val srcPort = ((packet[ipHeaderLength].toInt() and 0xFF) shl 8) or (packet[ipHeaderLength + 1].toInt() and 0xFF)
            val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or (packet[ipHeaderLength + 3].toInt() and 0xFF)
            val seq = readTcpSeq(packet, ipHeaderLength) + 1

            val rstPacket = buildTcpPacket(
                srcIp = destIp, srcPort = destPort,
                destIp = srcIp, destPort = srcPort,
                seq = 0, ack = seq,
                flags = 0x14,
                payload = ByteArray(0)
            )
            outputStream.write(rstPacket)
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending RST packet", e)
        }
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF))
            i += 2
        }
        if (i < offset + length) {
            sum += ((data[i].toInt() and 0xFF) shl 8)
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun calculateTcpChecksum(packet: ByteArray, ipHeaderLength: Int, tcpHeaderLength: Int, payloadLength: Int): Int {
        val tcpLength = tcpHeaderLength + payloadLength
        val pseudoHeaderLength = 12
        val totalLength = pseudoHeaderLength + tcpLength
        val data = ByteArray(totalLength)

        for (i in 0 until 4) {
            data[i] = packet[12 + i]
            data[4 + i] = packet[16 + i]
        }

        data[8] = 0
        data[9] = 6.toByte()
        data[10] = (tcpLength shr 8).toByte()
        data[11] = tcpLength.toByte()

        packet.copyInto(data, pseudoHeaderLength, ipHeaderLength, ipHeaderLength + tcpLength)
        data[pseudoHeaderLength + 16] = 0
        data[pseudoHeaderLength + 17] = 0

        return calculateChecksum(data, 0, totalLength)
    }

    private fun isVirtualIp(ip: String): Boolean {
        return ip.startsWith(VIRTUAL_IP_PREFIX)
    }

    private fun getVirtualIpForDomain(domain: String): String {
        return domainVirtualIpMap.getOrPut(domain) {
            nextVirtualIp++
            if (nextVirtualIp > 254) {
                nextVirtualIp = 2
            }
            "${VIRTUAL_IP_PREFIX}1.$nextVirtualIp"
        }
    }

    private fun ipToString(ip: ByteArray): String {
        return "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
    }

    private fun processUdpPacket(packet: ByteArray, ipHeaderLength: Int, outputStream: FileOutputStream) {
        val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or (packet[ipHeaderLength + 3].toInt() and 0xFF)
        val destIp = byteArrayOf(packet[16], packet[17], packet[18], packet[19])
        val destIpStr = ipToString(destIp)

        if (destPort == 53) {
            handleDnsPacket(packet, ipHeaderLength, outputStream)
        } else {
            Log.v(TAG, "Ignoring non-DNS UDP traffic: $destIpStr:$destPort")
        }
    }

    private fun sendHttpConnect(socket: Socket, domain: String, port: Int) {
        val connectRequest = "CONNECT $domain:$port HTTP/1.1\r\nHost: $domain:$port\r\n\r\n"
        socket.getOutputStream().write(connectRequest.toByteArray())
        socket.getOutputStream().flush()

        // Read CONNECT response using raw InputStream to avoid BufferedReader pre-reading payload bytes
        val inputStream = socket.getInputStream()
        val buffer = ByteArray(4096)
        val responseBuilder = StringBuilder()
        var headerEnd = -1

        while (headerEnd == -1) {
            val read = inputStream.read(buffer)
            if (read <= 0) {
                throw Exception("Empty CONNECT response")
            }
            responseBuilder.append(String(buffer, 0, read))
            headerEnd = responseBuilder.indexOf("\r\n\r\n")
        }

        val response = responseBuilder.substring(0, headerEnd).split("\r\n")[0]
        if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200")) {
            throw Exception("CONNECT failed: $response")
        }
        Log.d(TAG, "HTTP CONNECT established for $domain:$port")
    }

    private fun startResponseReader(connectionKey: String, outputStream: FileOutputStream) {
        CoroutineScope(Dispatchers.IO).launch {
            val state = tcpConnections[connectionKey]
            if (state == null) {
                Log.w(TAG, "No state for response reader: $connectionKey")
                return@launch
            }

            try {
                val inputStream = state.socket.getInputStream()
                val buffer = ByteArray(1200)
                while (isRunning && state.socket.isConnected && !state.socket.isClosed) {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        val payload = buffer.copyOf(length)
                        val responsePacket = buildTcpPacket(
                            srcIp = state.virtualIp, srcPort = state.serverPort,
                            destIp = state.clientIp, destPort = state.clientPort,
                            seq = state.serverSeq, ack = state.serverAck,
                            flags = 0x18, // PSH + ACK
                            payload = payload
                        )
                        outputStream.write(responsePacket)
                        outputStream.flush()
                        state.serverSeq += payload.size
                    } else if (length == -1) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Error reading proxy response", e)
            } finally {
                closeTcpConnection(connectionKey)
            }
        }
    }

    private fun handleDnsPacket(packet: ByteArray, ipHeaderLength: Int, outputStream: FileOutputStream) {
        try {
            val udpHeaderLength = 8
            val dnsPayload = packet.copyOfRange(ipHeaderLength + udpHeaderLength, packet.size)
            val domain = extractDomainFromDnsQuery(dnsPayload)

            Log.d(TAG, "DNS query for: $domain")

            val response = if (domain.isNotEmpty() && shouldProxyDomain(domain)) {
                val virtualIp = getVirtualIpForDomain(domain)
                virtualIpDomainMap[virtualIp] = domain
                Log.d(TAG, "DNS response (proxied): $domain -> $virtualIp")
                createDnsResponse(dnsPayload, virtualIp)
            } else {
                queryRealDns(dnsPayload)
            }

            val destIp = byteArrayOf(packet[12], packet[13], packet[14], packet[15])
            val destPort = ((packet[ipHeaderLength].toInt() and 0xFF) shl 8) or (packet[ipHeaderLength + 1].toInt() and 0xFF)
            val responseIpPacket = createIpPacket(response, ipToString(destIp), destPort, 17)
            outputStream.write(responseIpPacket)
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling DNS", e)
        }
    }

    private fun shouldProxyDomain(domain: String): Boolean {
        return allowedDomains.any { allowedDomain ->
            domain == allowedDomain || domain.endsWith(".$allowedDomain")
        }
    }

    private fun extractDomainFromDnsQuery(query: ByteArray): String {
        if (query.size < 12) return ""

        var offset = 12
        val domainBuilder = StringBuilder()
        while (offset < query.size && query[offset] != 0.toByte()) {
            val length = query[offset].toInt() and 0xFF
            if (length == 0) break
            offset++
            for (j in 0 until length) {
                if (offset + j < query.size) {
                    domainBuilder.append(query[offset + j].toInt().toChar())
                }
            }
            offset += length
            if (offset < query.size && query[offset] != 0.toByte()) {
                domainBuilder.append('.')
            }
        }
        return domainBuilder.toString()
    }

    private fun queryRealDns(dnsPayload: ByteArray): ByteArray {
        val dnsServers = cachedDnsServers.filter { !isVirtualIp(it) && it != VIRTUAL_DNS_IP }
        if (dnsServers.isEmpty()) {
            Log.w(TAG, "No cached system DNS servers, using fallback")
            cachedDnsServers = listOf("8.8.8.8", "8.8.4.4", "223.5.5.5")
        }

        val serversToTry = cachedDnsServers.filter { !isVirtualIp(it) && it != VIRTUAL_DNS_IP }
        var lastException: Exception? = null
        for (dnsServer in serversToTry) {
            val socket = java.net.DatagramSocket()
            socket.soTimeout = 8000
            protect(socket)

            try {
                Log.d(TAG, "Querying DNS server: $dnsServer")
                val sendPacket = java.net.DatagramPacket(dnsPayload, dnsPayload.size, java.net.InetAddress.getByName(dnsServer), 53)
                socket.send(sendPacket)

                val responseBuffer = ByteArray(1500)
                val responsePacket = java.net.DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)
                Log.d(TAG, "DNS response received from $dnsServer")
                return responsePacket.data.copyOf(responsePacket.length)
            } catch (e: Exception) {
                Log.w(TAG, "DNS query failed for $dnsServer: ${e.message}")
                lastException = e
            } finally {
                socket.close()
            }
        }

        throw lastException ?: Exception("All DNS queries failed")
    }

    private fun getSystemDnsServers(): List<String> {
        val servers = mutableListOf<String>()
        try {
            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            val activeNetwork = connectivityManager.activeNetwork
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)

            linkProperties?.dnsServers?.forEach { address ->
                val host = address.hostAddress
                if (host != null && !isVirtualIp(host) && host != VIRTUAL_DNS_IP) {
                    servers.add(host)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting system DNS servers", e)
        }

        if (servers.isEmpty()) {
            servers.add("8.8.8.8")
            servers.add("8.8.4.4")
        }

        return servers
    }

    private fun createDnsResponse(query: ByteArray, ip: String): ByteArray {
        val ipBytes = ip.split(".").map { it.toInt().toByte() }.toByteArray()
        val response = ByteArray(query.size + 16)
        query.copyInto(response, 0, 0, query.size)

        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()
        response[7] = 0x01.toByte()

        var offset = query.size
        response[offset++] = 0xC0.toByte()
        response[offset++] = 0x0C.toByte()
        response[offset++] = 0x00.toByte()
        response[offset++] = 0x01.toByte()
        response[offset++] = 0x00.toByte()
        response[offset++] = 0x01.toByte()
        response[offset++] = 0x00.toByte()
        response[offset++] = 0x00.toByte()
        response[offset++] = 0x00.toByte()
        response[offset++] = 0x3C.toByte()
        response[offset++] = 0x00.toByte()
        response[offset++] = 0x04.toByte()
        for (b in ipBytes) {
            response[offset++] = b
        }

        return response
    }

    private fun createIpPacket(payload: ByteArray, destIp: String, destPort: Int, protocol: Int): ByteArray {
        val ipHeaderLength = 20
        val tcpHeaderLength = if (protocol == 6) 20 else 8
        val totalLength = ipHeaderLength + tcpHeaderLength + payload.size

        val buffer = ByteBuffer.allocate(totalLength)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put((0x45).toByte())
        buffer.put((0x00).toByte())
        buffer.putShort(totalLength.toShort())
        buffer.putShort(0x0000.toShort())
        buffer.putShort(0x4000.toShort())
        buffer.put(0xFF.toByte())
        buffer.put(protocol.toByte())
        buffer.putShort(0x0000.toShort())

        val srcIp = byteArrayOf(10, 0, 0, 2)
        buffer.put(srcIp)

        val destIpBytes = destIp.split(".").map { it.toInt().toByte() }.toByteArray()
        buffer.put(destIpBytes)

        if (protocol == 6) {
            buffer.putShort(8080.toShort())
            buffer.putShort(destPort.toShort())
            buffer.putInt(0)
            buffer.putInt(0)
            buffer.put((0x50).toByte())
            buffer.put(0x00.toByte())
            buffer.putShort(65535.toShort())
            buffer.putShort(0x0000.toShort())
            buffer.putShort(0x0000.toShort())
        } else {
            buffer.putShort(53.toShort())
            buffer.putShort(destPort.toShort())
            buffer.putShort((8 + payload.size).toShort())
            buffer.putShort(0x0000.toShort())
        }

        buffer.put(payload)

        return buffer.array()
    }

    private fun sendPacket(packet: ByteArray, outputStream: FileOutputStream) {
        try {
            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
            val protocol = packet[9].toInt()

            if (protocol == 6) {
                val tcpHeaderLength = ((packet[ipHeaderLength + 12].toInt() and 0xF0) shr 4) * 4
                val destIp = byteArrayOf(packet[16], packet[17], packet[18], packet[19])
                val destIpStr = "${destIp[0].toInt() and 0xFF}.${destIp[1].toInt() and 0xFF}.${destIp[2].toInt() and 0xFF}.${destIp[3].toInt() and 0xFF}"
                val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or (packet[ipHeaderLength + 3].toInt() and 0xFF)

                if (destPort == 0) {
                    Log.w(TAG, "Invalid port 0, skipping packet to $destIpStr")
                    return
                }

                val socket = Socket()
                protect(socket)
                socket.connect(java.net.InetSocketAddress(destIpStr, destPort))

                val payloadOffset = ipHeaderLength + tcpHeaderLength
                if (payloadOffset < packet.size) {
                    socket.getOutputStream().write(packet, payloadOffset, packet.size - payloadOffset)
                    socket.getOutputStream().flush()

                    val responseBuffer = ByteArray(1500)
                    val responseLength = socket.getInputStream().read(responseBuffer)
                    if (responseLength > 0) {
                        val responsePacket = createIpPacket(responseBuffer.copyOf(responseLength), destIpStr, destPort, 6)
                        outputStream.write(responsePacket)
                        outputStream.flush()
                    }
                }
                socket.close()
            } else {
                val destIp = byteArrayOf(packet[16], packet[17], packet[18], packet[19])
                val destIpStr = "${destIp[0].toInt() and 0xFF}.${destIp[1].toInt() and 0xFF}.${destIp[2].toInt() and 0xFF}.${destIp[3].toInt() and 0xFF}"
                val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or (packet[ipHeaderLength + 3].toInt() and 0xFF)

                if (destPort == 0) {
                    Log.w(TAG, "Invalid port 0, skipping packet to $destIpStr")
                    return
                }

                val socket = java.net.DatagramSocket()
                protect(socket)

                val payloadOffset = ipHeaderLength + 8
                if (payloadOffset < packet.size) {
                    val payload = packet.copyOfRange(payloadOffset, packet.size)
                    val sendPacket = java.net.DatagramPacket(payload, payload.size, java.net.InetAddress.getByName(destIpStr), destPort)
                    socket.send(sendPacket)

                    val responseBuffer = ByteArray(1500)
                    val responsePacket = java.net.DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(responsePacket)

                    val ipPacket = createIpPacket(responsePacket.data.copyOf(responsePacket.length), destIpStr, destPort, 17)
                    outputStream.write(ipPacket)
                    outputStream.flush()
                }
                socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nexa VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
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
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Nexa VPN")
            .setContentText("VPN is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .apply {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    setPriority(Notification.PRIORITY_LOW)
                }
            }
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectJob?.cancel()
        serviceScope.cancel()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback: ${e.message}")
        }
        stopVpn()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isUserStarted) {
            Log.d(TAG, "Task removed, restarting VPN service")
            val restartIntent = Intent(this, NexaVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROXY_PORT, proxyPort)
                putStringArrayListExtra(EXTRA_DOMAINS, ArrayList(allowedDomains))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
    }

    companion object {
        const val ACTION_START = "com.nexa.pipe.vpn.START"
        const val ACTION_STOP = "com.nexa.pipe.vpn.STOP"
        const val EXTRA_PROXY_PORT = "proxy_port"
        const val EXTRA_DOMAINS = "domains"
        private const val CHANNEL_ID = "nexa_vpn_channel"
        private const val NOTIFICATION_ID = 1
    }
}