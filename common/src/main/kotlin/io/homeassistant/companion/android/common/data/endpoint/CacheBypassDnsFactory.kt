package io.homeassistant.companion.android.common.data.endpoint

/**
 * Builds the DNS chain for a lookup that must not read the device resolver's cache.
 *
 * It is a factory and not a fixed chain because the right answer depends on how far the user wants to
 * get away from a cache, which is a per-server setting and can change at any time:
 *
 * * the [authoritative][EndpointSettings.queryAuthoritativeDns] nameservers of the zone, which hold
 *   the published value immediately and are the only source that ignores the record's TTL;
 * * an [explicit server][EndpointSettings.customDnsServers], which still reads a cache but the one
 *   closest to where the record is hosted;
 * * otherwise a public resolver, which is a different cache from the device's own.
 */
fun interface CacheBypassDnsFactory {
    fun create(settings: EndpointSettings): DnsClient
}
