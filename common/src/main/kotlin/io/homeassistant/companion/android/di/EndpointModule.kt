package io.homeassistant.companion.android.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.data.endpoint.AndroidEndpointConnectivity
import io.homeassistant.companion.android.common.data.endpoint.DnsClient
import io.homeassistant.companion.android.common.data.endpoint.DohDnsClient
import io.homeassistant.companion.android.common.data.endpoint.EndpointConnectivity
import io.homeassistant.companion.android.common.data.endpoint.EndpointProbe
import io.homeassistant.companion.android.common.data.endpoint.OkHttpEndpointProbe
import javax.inject.Singleton

/**
 * Bindings for the dynamic endpoint resolver.
 *
 * These are the only platform-specific parts of the resolver; everything else is plain Kotlin so it
 * can be exercised by JVM unit tests.
 *
 * * [DnsClient] uses DNS over HTTPS because the platform resolver cannot query `TXT` or `SRV`.
 * * [EndpointProbe] uses a stock OkHttp client, never one that relaxes TLS verification.
 * * [EndpointConnectivity] is used to skip pointless lookups while the device is offline.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object EndpointModule {

    @Provides
    @Singleton
    fun provideDnsClient(): DnsClient = DohDnsClient()

    @Provides
    @Singleton
    fun provideEndpointProbe(): EndpointProbe = OkHttpEndpointProbe()

    @Provides
    @Singleton
    fun provideEndpointConnectivity(@ApplicationContext context: Context): EndpointConnectivity =
        AndroidEndpointConnectivity(context)
}
