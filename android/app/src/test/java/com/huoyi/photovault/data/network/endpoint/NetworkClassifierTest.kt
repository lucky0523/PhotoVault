package com.huoyi.photovault.data.network.endpoint

import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkClassifierTest {

    @Test
    fun `no network is NONE`() {
        assertEquals(NetworkClass.NONE, classify(hasNetwork = false, transports = emptySet()))
        assertEquals(NetworkClass.NONE, classify(hasNetwork = false, transports = setOf(TRANSPORT_WIFI)))
    }

    @Test
    fun `wifi and ethernet are LOCAL_CAPABLE`() {
        assertEquals(NetworkClass.LOCAL_CAPABLE, classify(true, setOf(TRANSPORT_WIFI)))
        assertEquals(NetworkClass.LOCAL_CAPABLE, classify(true, setOf(TRANSPORT_ETHERNET)))
    }

    @Test
    fun `vpn wins over wifi`() {
        assertEquals(NetworkClass.OTHER, classify(true, setOf(TRANSPORT_WIFI, TRANSPORT_VPN)))
        assertEquals(NetworkClass.OTHER, classify(true, setOf(TRANSPORT_ETHERNET, TRANSPORT_VPN)))
    }

    @Test
    fun `cellular bluetooth and unknown are OTHER`() {
        assertEquals(NetworkClass.OTHER, classify(true, setOf(TRANSPORT_CELLULAR)))
        assertEquals(NetworkClass.OTHER, classify(true, setOf(TRANSPORT_BLUETOOTH)))
        assertEquals(NetworkClass.OTHER, classify(true, emptySet()))
    }
}
