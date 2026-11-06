package app.ivynote.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the device has a usable network, as it changes. */
fun networkAvailable(context: Context): Flow<Boolean> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val up = HashSet<Network>()
    fun report() { trySend(synchronized(up) { up.isNotEmpty() }) }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { synchronized(up) { up += network }; report() }
        override fun onLost(network: Network) { synchronized(up) { up -= network }; report() }
    }
    val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
    trySend(cm.activeNetwork != null)
    cm.registerNetworkCallback(request, callback)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
