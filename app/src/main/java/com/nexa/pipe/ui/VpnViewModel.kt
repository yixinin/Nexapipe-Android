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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

                for (node in nodes.value) {
                    if (node.domains.isNotEmpty()) {
                        val domainsStr = node.domains.joinToString(",")
                        addLog("Adding node: ${node.nodeId} with domains: $domainsStr")
                        val result = IrohProxy.nativeAddNode(node.nodeId, domainsStr)
                        if (result != 0) {
                            addLog("Failed to add node: ${node.nodeId}")
                        }
                    }
                }

                if (nodes.value.isEmpty()) {
                    throw Exception("No nodes configured. Please add nodes with domains.")
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

                val allDomains = nodes.value.flatMap { it.domains }
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
            }
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