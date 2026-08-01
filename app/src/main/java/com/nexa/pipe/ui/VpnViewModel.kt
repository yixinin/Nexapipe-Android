package com.nexa.pipe.ui

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.pipe.IrohProxy
import com.nexa.pipe.PermissionManager
import com.nexa.pipe.SettingsManager
import com.nexa.pipe.vpn.NexaVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
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
    val proxyPort = kotlinx.coroutines.flow.MutableStateFlow("8080")
    val logMessages = kotlinx.coroutines.flow.MutableStateFlow(mutableListOf<String>())
    val errorMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val connectionStatusText = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val vpnPermissionGranted = kotlinx.coroutines.flow.MutableStateFlow(false)
    val notificationPermissionGranted = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun initSettings(context: android.content.Context) {
        if (settingsManager == null) {
            settingsManager = SettingsManager(context)
        }
    }

    fun loadSettings() {
        settingsManager?.let { manager ->
            val loadedNodes = manager.loadNodes()
            nodes.value = loadedNodes.toMutableList()
            proxyPort.value = manager.loadProxyPort()
            addLog("Settings loaded: ${loadedNodes.size} nodes, port ${proxyPort.value}")
        }
    }

    private fun saveSettings() {
        settingsManager?.let { manager ->
            manager.saveNodes(nodes.value)
            manager.saveProxyPort(proxyPort.value)
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
                addLog("Starting iroh...")
                val id = IrohProxy.nativeStartIroh()
                if (id != null) {
                    endpointId.value = id
                    isIrohStarted.value = true
                    addLog("Iroh started: $id")
                } else {
                    addLog("Failed to start iroh")
                }
            } catch (e: Exception) {
                addLog("Error starting iroh: ${e.message}")
                errorMessage.value = e.message ?: "Unknown error"
            }
        }
    }

    fun connect(context: Context) {
        if (isConnecting.value) return

        viewModelScope.launch(Dispatchers.IO) {
            isConnecting.value = true
            errorMessage.value = null
            connectionStatusText.value = null

            try {
                if (!IrohProxy.isNativeLoaded()) {
                    throw Exception("Native library not loaded. Please check if libnexapipe_client.so is properly included in the APK.")
                }

                val vpnPermissionGranted = VpnService.prepare(context) == null
                if (!vpnPermissionGranted) {
                    throw Exception("VPN permission not granted. Please grant VPN permission first.")
                }

                if (!isIrohStarted.value) {
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

                addLog("Starting proxy...")
                val basePort = proxyPort.value.toInt()

                IrohProxy.nativeStopProxy()
                addLog("Stopped any existing proxy, waiting for port release...")
                delay(500)

                var result = -1
                var actualPort = basePort
                for (attempt in 0..9) {
                    actualPort = basePort + attempt
                    addLog("Trying to start proxy on port $actualPort...")
                    result = IrohProxy.nativeStartProxy(actualPort)
                    if (result == 0) {
                        break
                    }
                    addLog("Failed to start proxy on port $actualPort, retrying...")
                    delay(200)
                }
                if (result != 0) {
                    throw Exception("Failed to start proxy on ports $basePort..${basePort + 9}")
                }
                addLog("Proxy started on port $actualPort")

                if (actualPort != basePort) {
                    proxyPort.value = actualPort.toString()
                }

                // Pre-connect: warm up iroh connection via local proxy before starting VPN
                val allDomains = nodes.value.flatMap { it.domains }

                if (allDomains.isNotEmpty()) {
                    connectionStatusText.value = "Pre-connecting..."
                    addLog("Pre-connecting to ${allDomains.size} domains...")
                    val preConnectedCount = preConnectAll(allDomains, actualPort)
                    connectionStatusText.value = null
                    addLog("Pre-connect completed: $preConnectedCount/${allDomains.size} domains succeeded")
                    if (preConnectedCount > 0) {
                        addLog("Pre-connect succeeded for at least one domain")
                        delay(1000)
                    } else {
                        addLog("All pre-connect attempts failed, starting VPN anyway")
                    }
                }

                val intent = Intent(context, NexaVpnService::class.java).apply {
                    action = NexaVpnService.ACTION_START
                    putExtra(NexaVpnService.EXTRA_PROXY_PORT, actualPort)
                    putStringArrayListExtra(NexaVpnService.EXTRA_DOMAINS, ArrayList(allDomains))
                }
                context.startForegroundService(intent)
                isVpnRunning.value = true
                addLog("VPN connected successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                errorMessage.value = e.message ?: "Unknown error"
                addLog("Connection failed: ${e.message}")
            } finally {
                isConnecting.value = false
                connectionStatusText.value = null
            }
        }
    }

    /**
     * Send a lightweight HTTP GET request through the local proxy to warm up the iroh
     * connection. Returns true if the backend responded (even with an error code),
     * false on timeout or network failure.
     */
    private suspend fun preConnect(domain: String, proxyPort: Int, maxRetries: Int = 2): Boolean {
        for (attempt in 0 until maxRetries) {
            val success = try {
                withTimeout(15_000) {
                    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
                    val url = java.net.URL("http://$domain/")
                    val conn = url.openConnection(proxy) as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
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
     */
    private suspend fun preConnectAll(domains: List<String>, proxyPort: Int): Int {
        addLog("Starting parallel pre-connect for ${domains.size} domains")
        connectionStatusText.value = "Pre-connecting..."
        
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
            try {
                val intent = Intent(context, NexaVpnService::class.java).apply {
                    action = NexaVpnService.ACTION_STOP
                }
                context.startService(intent)
                IrohProxy.nativeStopProxy()
                isVpnRunning.value = false
                addLog("VPN disconnected")
            } catch (e: Exception) {
                errorMessage.value = e.message ?: "Unknown error"
                addLog("Disconnection failed: ${e.message}")
            }
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

    fun updateProxyPort(port: String) {
        proxyPort.value = port
        saveSettings()
    }

    fun clearLogs() {
        logMessages.value.clear()
        logMessages.value = logMessages.value.toMutableList()
    }
}