package com.arkiv.player.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

private const val TAG = "CompanionDiscovery"
private const val MAX_RETRIES = 3
private const val RETRY_DELAY_MS = 1000L

data class DiscoveredHost(val name: String, val deviceId: String, val ip: String, val port: Int, val serviceName: String)

class CompanionDiscovery(context: Context, private val identity: CompanionIdentity) {
    companion object { const val SERVICE_TYPE = "_kino._tcp." }

    private val appCtx = context.applicationContext
    private val nsd = appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appCtx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val lock = wifi.createMulticastLock("arkiv-companion").apply { setReferenceCounted(true) }

    private val handler = Handler(Looper.getMainLooper())
    private var registrationRetries = 0
    private var discoveryRetries = 0
    private var lastHostPort = 0

    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    /** Registers the host service. Resets the registration retry counter -- callers should always
     *  call this (not [registerService]) to (re)start a fresh registration; [onRegistrationFailed]
     *  drives its own bounded retries via [registerService] directly. */
    fun registerHost(port: Int) {
        lastHostPort = port
        registrationRetries = 0
        registerService(port)
    }

    private fun registerService(port: Int) {
        unregisterHost()
        runCatching { lock.acquire() }
        val info = NsdServiceInfo().apply {
            serviceName = "KINO-${identity.deviceName}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", identity.deviceId)
            setAttribute("name", identity.deviceName)
            setAttribute("role", "host")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                Log.i(TAG, "registered ${s.serviceName}")
                registrationRetries = 0
            }
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {
                Log.w(TAG, "reg failed $e")
                if (registrationRetries < MAX_RETRIES) {
                    registrationRetries++
                    handler.postDelayed({ registerService(lastHostPort) }, RETRY_DELAY_MS)
                } else {
                    Log.w(TAG, "reg failed $e, giving up after $MAX_RETRIES retries")
                }
            }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        regListener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun unregisterHost() {
        regListener?.let { runCatching { nsd.unregisterService(it) } }
        regListener = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    /** Starts mDNS browsing. Resets the discovery retry counter -- callers should always call this
     *  (not [browseServices]) to (re)start a fresh browse; [onStartDiscoveryFailed] drives its own
     *  bounded retries via [browseServices] directly. */
    fun startBrowsing() {
        discoveryRetries = 0
        browseServices()
    }

    private fun browseServices() {
        stopBrowsing()
        runCatching { lock.acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { discoveryRetries = 0 }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {
                Log.w(TAG, "browse start failed $e")
                stopBrowsing()
                if (discoveryRetries < MAX_RETRIES) {
                    discoveryRetries++
                    handler.postDelayed({ browseServices() }, RETRY_DELAY_MS)
                } else {
                    Log.w(TAG, "browse start failed $e, giving up after $MAX_RETRIES retries")
                }
            }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) { resolve(s) }
            override fun onServiceLost(s: NsdServiceInfo) {
                _hosts.value = _hosts.value.filterNot { it.serviceName == s.serviceName }
            }
        }
        discListener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stopBrowsing() {
        discListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discListener = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    private fun resolve(service: NsdServiceInfo) {
        val rl = object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, e: Int) { Log.w(TAG, "resolve failed $e") }
            override fun onServiceResolved(s: NsdServiceInfo) {
                val host: InetAddress = s.host ?: return
                val id = s.attributes["id"]?.let { String(it, Charsets.UTF_8) } ?: s.serviceName
                val name = s.attributes["name"]?.let { String(it, Charsets.UTF_8) } ?: s.serviceName
                val found = DiscoveredHost(name, id, host.hostAddress ?: return, s.port, s.serviceName)
                _hosts.value = (_hosts.value.filterNot { it.deviceId == found.deviceId } + found)
            }
        }
        runCatching { nsd.resolveService(service, rl) }
    }
}
