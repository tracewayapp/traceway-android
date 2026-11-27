package com.tracewayapp.traceway.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** Whether the connection is unmetered, as the `wifi` replay image policy asks it, followed live by a callback. */
@RunWith(RobolectricTestRunner::class)
class NetworkStateTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun capabilities(vararg capabilities: Int): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        for (c in capabilities) shadowOf(caps).addCapability(c)
        return caps
    }

    @Test
    fun followsTheDefaultNetworkAsItChanges() {
        val state = NetworkState(context)
        val callback = shadowOf(manager).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(1)

        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        assertTrue("Wi-Fi", state.unmetered)
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse("cellular or a hotspot", state.unmetered)
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
        assertTrue("a carrier's unmetered period", state.unmetered)

        state.close()
        assertEquals(0, shadowOf(manager).networkCallbacks.size)
    }
}
