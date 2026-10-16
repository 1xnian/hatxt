package io.homeassistant.companion.android.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.data.endpoint.AndroidDnsServerProvider
import io.homeassistant.companion.android.common.data.endpoint.AndroidEndpointConnectivity
import io.homeassistant.companion.android.common.data.endpoint.DnsClient
import io.homeassistant.companion.android.common.data.endpoint.DohDnsClient
import io.homeassistant.companion.android.common.data.endpoint.EndpointConnectivity
import io.homeassistant.companion.android.common.data.endpoint.EndpointProbe
import io.homeassistant.companion.android.common.data.endpoint.FallbackDnsClient
import io.homeassistant.companion.android.common.data.endpoint.OkHttpEndpointProbe
import io.homeassistant.companion.android.common.data.endpoint.RawUdpDnsClient
import io.homeassistant.companion.android.common.data.endpoint.SystemDnsClient
import javax.inject.Singleton

/**
 * Bindings for the dynamic endpoint resolver.
 *
 * The resolver itself is plain Kotlin so it can be exercised by JVM unit tests; only the transport
 * implementations are platform specific.
 *
 * ## Why [DnsClient] is a chain
 *
 * There is no single transport that can query `TXT`/`SRV` on every device and every network, so the
 * resolvers are ordered from "most likely to already work" to "last resort":
 *
 * 1. [SystemDnsClient] — the platform resolver, i.e. the DNS servers this device is configured with
 *    (API 29+).
 * 2. [RawUdpDnsClient] — the same servers over plain UDP, which also covers API 23-28 where the
 *    platform resolver cannot be asked for `TXT`.
 * 3. DNS over HTTPS, domestic resolvers first and the international ones as a fallback, for
 *    networks where plain DNS is blocked or rewritten.
 *
 * Every attempt is recorded, so a failure can name the stage that broke instead of reporting a
 * generic "lookup failed". See [FallbackDnsClient].
 *
 * [EndpointProbe] uses a stock OkHttp client, never one that relaxes TLS verification.
 * [EndpointConnectivity] is used to skip pointless lookups while the device is offline.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object EndpointModule {

    @Provides
    @Singleton
    fun provideDnsClient(@ApplicationContext context: Context): DnsClient = FallbackDnsClient(
        clients = buildList {
            SystemDnsClient.create(context)?.let { client ->
                add(FallbackDnsClient.Entry(SystemDnsClient.PROVIDER, client))
            }
            add(
                FallbackDnsClient.Entry(
                    RawUdpDnsClient.PROVIDER,
                    RawUdpDnsClient(AndroidDnsServerProvider(context)),
                ),
            )
            DohDnsClient.DEFAULT_ENDPOINTS.forEach { endpoint ->
                add(
                    FallbackDnsClient.Entry(
                        DohDnsClient.providerName(endpoint),
                        DohDnsClient(endpoints = listOf(endpoint)),
                    ),
                )
            }
        },
    )

    @Provides
    @Singleton
    fun provideEndpointProbe(): EndpointProbe = OkHttpEndpointProbe()

    @Provides
    @Singleton
    fun provideEndpointConnectivity(@ApplicationContext context: Context): EndpointConnectivity =
        AndroidEndpointConnectivity(context)
}
