package io.homeassistant.companion.android.common.data.endpoint

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * [EndpointProbe] backed by a plain OkHttp client.
 *
 * ## What the probe is allowed to do
 *
 * It performs one request and classifies the outcome. It deliberately uses a **stock** client:
 * no custom `HostnameVerifier`, no custom `X509TrustManager`, no authentication interceptor. The
 * specification forbids disabling certificate verification, and a probe that "succeeded" by
 * ignoring a bad certificate would defeat the whole downgrade policy. An untrusted certificate is
 * therefore reported as [EndpointProbeFailure.TLS_CERT_UNTRUSTED] and the policy refuses to
 * downgrade.
 *
 * ## Method choice
 *
 * `HEAD` is used first because it proves the TLS handshake and the HTTP route without transferring
 * a body. Home Assistant serves `HEAD` for its frontend, but a proxy in front of it may reject it
 * with `405`/`501`; that would be misread as a server error, so those two status codes trigger a
 * single `GET` retry. Neither status ever leads to a downgrade on its own.
 *
 * Redirects are not followed: a redirect to a different origin cannot be validated with the
 * certificate of this one.
 */
internal class OkHttpEndpointProbe(
    private val client: OkHttpClient = defaultClient(),
) : EndpointProbe {

    override fun probe(endpoint: ResolvedEndpoint): EndpointProbeFailure {
        val headOutcome = execute(endpoint, head = true)
        if (headOutcome.needsGetRetry) {
            return execute(endpoint, head = false).failure
        }
        return headOutcome.failure
    }

    private data class Outcome(
        val failure: EndpointProbeFailure,
        val needsGetRetry: Boolean = false,
    )

    private fun execute(endpoint: ResolvedEndpoint, head: Boolean): Outcome {
        val request = Request.Builder()
            .url(endpoint.url)
            .header("User-Agent", USER_AGENT)
            .apply { if (head) head() else get() }
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                Outcome(
                    failure = HttpsFallbackPolicy.classifyHttpStatus(response.code),
                    needsGetRetry = head && response.code in METHOD_NOT_ALLOWED_CODES,
                )
            }
        } catch (e: Exception) {
            Outcome(classifyFailure(e))
        }
    }

    private fun classifyFailure(throwable: Throwable): EndpointProbeFailure {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            when (current) {
                is UnknownHostException -> return EndpointProbeFailure.DNS_FAILURE
                is NoRouteToHostException -> return EndpointProbeFailure.NO_ROUTE_TO_HOST
                is ConnectException -> return EndpointProbeFailure.CONNECTION_REFUSED
                is SSLPeerUnverifiedException -> return EndpointProbeFailure.TLS_CERT_UNTRUSTED
                is CertificateException -> return EndpointProbeFailure.TLS_CERT_UNTRUSTED
                is SSLHandshakeException -> return HttpsFallbackPolicy.classifyTlsFailureMessage(current.message)
                is SSLException -> return HttpsFallbackPolicy.classifyTlsFailureMessage(current.message)
                is SocketTimeoutException -> return EndpointProbeFailure.CONNECT_TIMEOUT
                is InterruptedIOException -> return EndpointProbeFailure.CONNECT_TIMEOUT
            }
            current = current.cause
            depth += 1
        }
        Timber.d(throwable, "Unclassified probe failure")
        return EndpointProbeFailure.UNKNOWN_NETWORK_ERROR
    }

    internal companion object {
        const val USER_AGENT = "HomeAssistant-Android/endpoint-probe"
        const val MAX_CAUSE_DEPTH = 8
        const val CONNECT_TIMEOUT_SECONDS = 6L
        const val READ_TIMEOUT_SECONDS = 6L
        const val CALL_TIMEOUT_SECONDS = 12L

        val METHOD_NOT_ALLOWED_CODES = setOf(405, 501)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
