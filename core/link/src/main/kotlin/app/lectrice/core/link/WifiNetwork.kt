package app.lectrice.core.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.ParcelFileDescriptor
import app.lectrice.core.data.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.lectrice_ffi.NetworkBinder

/**
 * The phone's Wi-Fi (or Ethernet) network, and the binder that puts the link's sockets on it (file 24 §3.10), so
 * mobile data or a VPN can never carry the link. Rust makes the socket unconnected; [bindSocket] binds it through a
 * duplicate of its descriptor (a duplicate shares the socket) with the public `Network.bindSocket`.
 */
internal class WifiNetwork(context: Context, private val onChanged: () -> Unit) : NetworkBinder {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = mutable.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mutable.value = network
            Log.i(SCOPE, "wifi available")
            onChanged()
        }

        override fun onLost(network: Network) {
            if (mutable.value == network) mutable.value = null
            Log.i(SCOPE, "wifi lost")
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
    }

    override fun bindSocket(fd: Int): Boolean {
        val network = mutable.value ?: return false
        return runCatching { ParcelFileDescriptor.fromFd(fd).use { duplicate -> network.bindSocket(duplicate.fileDescriptor) } }
            .onFailure { Log.w(SCOPE, "socket not bound to wifi", it) }
            .isSuccess
    }

    private companion object {
        const val SCOPE = "Wifi"
    }
}
