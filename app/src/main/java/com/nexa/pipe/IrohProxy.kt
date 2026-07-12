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
    
    external fun nativeStartIroh(): String?
    
    external fun nativeStartProxy(listenPort: Int): Int
    
    external fun nativeStartProxyLegacy(listenPort: Int, targetEndpointId: String): Int
    
    external fun nativeStopProxy(): Int
    
    external fun nativeAddNode(nodeId: String, domains: String): Int
    
    external fun nativeRemoveNode(nodeId: String): Int
    
    external fun nativeClearNodes(): Int
    
    external fun nativeAddDomain(domain: String): Int
    
    external fun nativeRemoveDomain(domain: String): Int
    
    external fun nativeDestroy(): Int
}