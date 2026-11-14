package app.lectrice.core.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.lectrice.core.data.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address

/** A Lectrice computer on this Wi-Fi: its `id` TXT record, the name it advertises and where it listens. */
data class Sighting(val peerId: String, val name: String, val address: String)

/**
 * Finds Lectrice computers with Android's `NsdManager` (DNS-SD `_lectrice._tcp`, file 24 §3.2). A computer is known by
 * its `id` TXT record only, never by name or address (both change). The desktop advertises IPv4 addresses on its home
 * network only; anything else is ignored here and refused by the link anyway.
 */
@Suppress("DEPRECATION") // resolveService: its replacement (registerServiceInfoCallback) needs API 34; minSdk is 29.
internal class ComputerFinder(context: Context, private val onSighting: (Sighting) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val mutable = MutableStateFlow<List<Sighting>>(emptyList())
    val sightings: StateFlow<List<Sighting>> = mutable.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    /** Android before 14 resolves one service at a time. */
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null) return
        mutable.value = emptyList()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Log.i(SCOPE, "discovery started")

            override fun onDiscoveryStopped(serviceType: String) = Log.i(SCOPE, "discovery stopped")

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(SCOPE, "discovery failed code=$errorCode")
                synchronized(this@ComputerFinder) { listener = null }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Log.w(SCOPE, "discovery stop failed code=$errorCode")

            override fun onServiceFound(service: NsdServiceInfo) = enqueue(service)

            override fun onServiceLost(service: NsdServiceInfo) {
                mutable.update { list -> list.filterNot { it.name == service.serviceName } }
            }
        }
        listener = discovery
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { Log.w(SCOPE, "discovery not started", it); listener = null }
    }

    @Synchronized
    fun stop() {
        val discovery = listener ?: return
        listener = null
        waiting.clear()
        runCatching { nsd.stopServiceDiscovery(discovery) }
    }

    /** A fresh browse, which reports the computers on the Wi-Fi again (only if it was browsing). */
    @Synchronized
    fun restart() {
        if (listener == null) return
        stop()
        start()
    }

    @Synchronized
    private fun enqueue(service: NsdServiceInfo) {
        waiting.addLast(service)
        if (!resolving) next()
    }

    @Synchronized
    private fun next() {
        val service = waiting.removeFirstOrNull()
        resolving = service != null
        service ?: return
        nsd.resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(SCOPE, "resolve failed code=$errorCode")
                    next()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    sightingOf(info)?.let { sighting ->
                        mutable.update { list -> list.filterNot { it.peerId == sighting.peerId } + sighting }
                        Log.i(SCOPE, "computer seen peer=${sighting.peerId.take(LOG_ID)}")
                        onSighting(sighting)
                    }
                    next()
                }
            },
        )
    }

    private fun sightingOf(info: NsdServiceInfo): Sighting? {
        val host = info.host as? Inet4Address ?: return null
        val attributes = info.attributes
        val version = attributes["v"]?.toString(Charsets.US_ASCII)
        val id = attributes["id"]?.toString(Charsets.US_ASCII)?.lowercase()
        if (version != PROTOCOL_MAJOR || id == null || !ID_PATTERN.matches(id)) return null
        return Sighting(id, info.serviceName.take(MAX_NAME), "${host.hostAddress}:${info.port}")
    }

    private companion object {
        const val SCOPE = "Nsd"
        const val SERVICE_TYPE = "_lectrice._tcp"
        const val PROTOCOL_MAJOR = "1"
        const val MAX_NAME = 40
        const val LOG_ID = 8
        val ID_PATTERN = Regex("[0-9a-f]{16}")
    }
}
