package com.nexa.pipe

import android.content.Context
import android.content.SharedPreferences
import com.nexa.pipe.ui.NodeConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("NexaPipeSettings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val KEY_NODES = "nodes"
        const val KEY_PROXY_PORT = "proxy_port"
    }

    fun saveNodes(nodes: List<NodeConfig>) {
        val jsonStr = json.encodeToString(nodes)
        prefs.edit().putString(KEY_NODES, jsonStr).apply()
    }

    fun loadNodes(): List<NodeConfig> {
        val jsonStr = prefs.getString(KEY_NODES, "")
        if (jsonStr.isNullOrEmpty()) {
            return emptyList()
        }
        return try {
            json.decodeFromString(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveProxyPort(port: String) {
        prefs.edit().putString(KEY_PROXY_PORT, port).apply()
    }

    fun loadProxyPort(): String {
        return prefs.getString(KEY_PROXY_PORT, "8080") ?: "8080"
    }
}
