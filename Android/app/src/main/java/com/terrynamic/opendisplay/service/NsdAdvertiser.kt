package com.terrynamic.opendisplay.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.terrynamic.opendisplay.protocol.WireProtocol
import java.util.concurrent.atomic.AtomicBoolean

class NsdAdvertiser(
    context: Context,
    private val onRegisteredName: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicast = wifi.createMulticastLock("opendisplay-mdns").apply {
        setReferenceCounted(false)
    }
    private val running = AtomicBoolean(false)
    private var registration: NsdManager.RegistrationListener? = null
    private var desiredName = WireProtocol.DEFAULT_SERVICE_NAME
    private var installId = ""
    private var port = WireProtocol.DEFAULT_PORT

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = reregister("network available")
        override fun onLost(network: Network) = reregister("network lost")
    }

    fun start(serviceName: String, id: String, listenPort: Int) {
        desiredName = serviceName
        installId = id
        port = listenPort
        if (!running.compareAndSet(false, true)) {
            reregister("start while running")
            return
        }
        try {
            multicast.acquire()
        } catch (e: Exception) {
            Log.w(WireProtocol.LOG_TAG, "multicast lock failed: ${e.message}")
        }
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(WireProtocol.LOG_TAG, "network callback failed: ${e.message}")
        }
        register()
    }

    fun rename(serviceName: String) {
        if (desiredName == serviceName) return
        desiredName = serviceName
        if (running.get()) reregister("rename")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        unregister()
        try {
            multicast.release()
        } catch (_: Exception) {
        }
    }

    private fun reregister(reason: String) {
        if (!running.get()) return
        Log.i(WireProtocol.LOG_TAG, "NSD re-register ($reason)")
        unregister()
        register()
    }

    private fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = desiredName
            serviceType = WireProtocol.SERVICE_TYPE
            port = this@NsdAdvertiser.port
            setAttribute("id", installId)
            setAttribute("pv", WireProtocol.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                Log.i(WireProtocol.LOG_TAG, "NSD registered as $name")
                onRegisteredName(name)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(WireProtocol.LOG_TAG, "NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(WireProtocol.LOG_TAG, "NSD unregistered ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(WireProtocol.LOG_TAG, "NSD unregistration failed: $errorCode")
            }
        }
        registration = listener
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            } else {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (e: Exception) {
            Log.w(WireProtocol.LOG_TAG, "NSD register threw: ${e.message}")
        }
    }

    private fun unregister() {
        val listener = registration ?: return
        registration = null
        try {
            nsd.unregisterService(listener)
        } catch (_: Exception) {
        }
    }
}
