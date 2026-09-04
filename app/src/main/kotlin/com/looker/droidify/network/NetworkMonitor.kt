package com.looker.droidify.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.looker.droidify.utility.common.extension.connectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the device has a connection that can actually reach the internet.
 *
 * Android is asked outright rather than the answer being inferred from something failing: a first
 * launch with no network has nothing to fail yet, its catalogue is empty, and its screen still has to
 * be able to say why instead of showing an empty page that reads as a broken app.
 *
 * Validated as well as "carries internet", which is Android's own record of traffic having got
 * through: a Wi-Fi joined but still stuck on a sign-in page carries nothing, and this is the same test
 * the queued sync waits on, so the screen and the sync agree on what counts as being online.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val online: Flow<Boolean> = callbackFlow {
        val manager = context.connectivityManager
        if (manager == null) {
            // Nothing to ask. Say online and let a real request be the one to fail, rather than blame
            // a connection that was never found wanting.
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        // Wi-Fi and mobile data can be up at once, so the usable ones are counted rather than the last
        // one heard from overwriting the others.
        val usable = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.reachesInternet()) usable += network else usable -= network
                trySend(usable.isNotEmpty())
            }

            override fun onLost(network: Network) {
                usable -= network
                trySend(usable.isNotEmpty())
            }
        }
        val active = manager.activeNetwork
        if (active != null && manager.getNetworkCapabilities(active)?.reachesInternet() == true) {
            usable += active
        }
        trySend(usable.isNotEmpty())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val listening = runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess
        // A device that refuses to be watched would otherwise be stuck on whatever it read above,
        // which on a launch with no network is a permanent "offline" nothing could ever clear.
        if (!listening) trySend(true)
        awaitClose {
            if (listening) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}

private fun NetworkCapabilities.reachesInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
