package io.homeassistant.companion.android.common.data.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import io.homeassistant.companion.android.common.util.SdkVersion
import javax.inject.Inject
import timber.log.Timber

@Suppress("DEPRECATION")
class WifiHelperImpl @Inject constructor(
    private val connectivityManager: ConnectivityManager,
    private val wifiManager: WifiManager?,
) : WifiHelper {
    override fun hasWifi(): Boolean = wifiManager != null

    override fun isUsingWifi(): Boolean = connectivityManager.activeNetwork?.let {
        connectivityManager
            .getNetworkCapabilities(it)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: false

    override fun isUsingSpecificWifi(networks: List<String>): Boolean {
        if (networks.isEmpty()) return false
        val formattedSsid = getWifiSsid()
        val formattedBssid = getWifiBssid()
        return (
            formattedSsid != null &&
                formattedSsid in networks
            ) ||
            (
                formattedBssid != null &&
                    formattedBssid != WifiHelper.INVALID_BSSID &&
                    networks.any {
                        it.startsWith(WifiHelper.BSSID_PREFIX) &&
                            it.removePrefix(WifiHelper.BSSID_PREFIX).equals(formattedBssid, ignoreCase = true)
                    }
                )
    }

    override fun getWifiSsid(): String? {
        // Deprecated but callbacks aren't instant.
        // Reading the SSID is location-gated: when system location is off (or the location
        // permission is missing) several OEM ROMs throw SecurityException /
        // IllegalStateException here instead of returning UNKNOWN_SSID. This is called from
        // the URL selection path (isInternal) during startup, so an uncaught throw would take
        // the whole app down on launch. Treat any failure as "SSID unknown".
        val rawSsid = try {
            wifiManager?.connectionInfo?.ssid
        } catch (e: Exception) {
            Timber.w(e, "Unable to read Wi-Fi SSID (location disabled or permission missing)")
            null
        } ?: return null

        return rawSsid
            .takeUnless {
                SdkVersion.isAtLeast(Build.VERSION_CODES.R) && it == WifiManager.UNKNOWN_SSID
            }
            ?.removeSurrounding("\"")
    }

    override fun getWifiBssid(): String? =
        // Deprecated but callback doesn't provide BSSID info instantly. Same location gating
        // applies as for the SSID above.
        try {
            wifiManager?.connectionInfo?.bssid
        } catch (e: Exception) {
            Timber.w(e, "Unable to read Wi-Fi BSSID (location disabled or permission missing)")
            null
        }
}
