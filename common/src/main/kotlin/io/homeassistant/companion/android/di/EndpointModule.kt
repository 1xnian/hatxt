package io.homeassistant.companion.android.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.data.endpoint.AndroidDnsServerProvider
import io.homeassistant.companion.android.common.data.endpoint.AndroidEndpointConnectivity
import io.homeassistant.companion.android.common.data.endpoint.AuthoritativeDnsClient
import io.homeassistant.companion.android.common.data.endpoint.CacheBypassDnsFactory
import io.homeassistant.companion.android.common.data.endpoint.DnsClient
import io.homeassistant.companion.android.common.data.endpoint.DnsServerProvider
import io.homeassistant.companion.android.common.data.endpoint.DohDnsClient
import io.homeassistant.companion.android.common.data.endpoint.EndpointConnectivity
import io.homeassistant.companion.android.common.data.endpoint.EndpointProbe
import io.homeassistant.companion.android.common.data.endpoint.FallbackDnsClient
import io.homeassistant.companion.android.common.data.endpoint.OkHttpEndpointProbe
import io.homeassistant.companion.android.common.data.endpoint.RawUdpDnsClient
import io.homeassistant.companion.android.common.data.endpoint.SystemDnsClient
import java.net.InetSocketAddress
import javax.inject.Singleton

private const val CUSTOM_SERVER_PROVIDER = "custom"
private const val DNS_PORT = 53

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

    /**
     * The chain used when a refresh must not read the device resolver's cache.
     *
     * A cached answer stops the chain, so asking the device resolver first means a record that
     * changed inside its TTL is never visible no matter how often the user refreshes: the local cache
     * answers and everything after it is never reached. Which chain replaces it depends on the
     * settings, see [CacheBypassDnsFactory].
     */
    @Provides
    @Singleton
    fun provideCacheBypassDnsFactory(
        @ApplicationContext context: Context,
        normalChain: DnsClient,
    ): CacheBypassDnsFactory = CacheBypassDnsFactory { settings ->
        when {
            // The zone's own servers: the only answer that ignores the TTL entirely.
            settings.queryAuthoritativeDns -> AuthoritativeDnsClient(bootstrap = normalChain)

            // A server the user named, typically the provider hosting the record.
            settings.customDnsServerList.isNotEmpty() -> FallbackDnsClient(
                clients = listOf(
                    FallbackDnsClient.Entry(
                        CUSTOM_SERVER_PROVIDER,
                        RawUdpDnsClient(DnsServerProvider { settings.customDnsServerList.toAddresses() }),
                    ),
                ) + publicFirstEntries(context),
            )

            // Otherwise a different cache from the device's own.
            else -> FallbackDnsClient(clients = publicFirstEntries(context))
        }
    }

    /**
     * Public resolvers first, the device's own last.
     *
     * [normalChain] is used as the bootstrap for the `NS` lookup: which servers are authoritative
     * changes rarely, so reading that from a cache is correct.
     */
    private fun publicFirstEntries(context: Context): List<FallbackDnsClient.Entry> = buildList {
        DohDnsClient.DEFAULT_ENDPOINTS.forEach { endpoint ->
            add(
                FallbackDnsClient.Entry(
                    DohDnsClient.providerName(endpoint),
                    DohDnsClient(endpoints = listOf(endpoint)),
                ),
            )
        }
        add(
            FallbackDnsClient.Entry(
                RawUdpDnsClient.PROVIDER,
                RawUdpDnsClient(AndroidDnsServerProvider(context)),
            ),
        )
        SystemDnsClient.create(context)?.let { client ->
            add(FallbackDnsClient.Entry(SystemDnsClient.PROVIDER, client))
        }
    }

    /** Keeps only the entries that are parseable; a typo must not break the whole chain. */
    private fun List<String>.toAddresses(): List<InetSocketAddress> =
        mapNotNull { runCatching { InetSocketAddress(it, DNS_PORT) }.getOrNull() }

    @Provides
    @Singleton
    fun provideEndpointProbe(): EndpointProbe = OkHttpEndpointProbe()

    @Provides
    @Singleton
    fun provideEndpointConnectivity(@ApplicationContext context: Context): EndpointConnectivity =
        AndroidEndpointConnectivity(context)
}
