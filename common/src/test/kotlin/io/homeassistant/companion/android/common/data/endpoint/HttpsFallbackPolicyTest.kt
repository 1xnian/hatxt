package io.homeassistant.companion.android.common.data.endpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Safety rules for the HTTPS to HTTP downgrade, section 6 of the specification.
 *
 * The tests encode the promise that credentials are never silently sent over plaintext and that a
 * working HTTPS endpoint is never replaced.
 */
class HttpsFallbackPolicyTest {

    private fun evaluate(
        failure: EndpointProbeFailure,
        allowFallback: Boolean = true,
        consent: Boolean = true,
    ) = HttpsFallbackPolicy.evaluate(failure, allowFallback, consent)

    @Test
    fun `a successful https probe needs no fallback`() {
        assertEquals(FallbackDecision.NOT_NEEDED, evaluate(EndpointProbeFailure.NONE).decision)
    }

    @Test
    fun `connection refused downgrades when enabled and consented`() {
        val result = evaluate(EndpointProbeFailure.CONNECTION_REFUSED)
        assertEquals(FallbackDecision.AUTO_DOWNGRADE, result.decision)
        assertTrue(result.maySwitchToHttp)
    }

    @Test
    fun `no route to host downgrades when enabled and consented`() {
        assertEquals(
            FallbackDecision.AUTO_DOWNGRADE,
            evaluate(EndpointProbeFailure.NO_ROUTE_TO_HOST).decision,
        )
    }

    @Test
    fun `downgrade is forbidden when the user disabled the feature`() {
        val result = evaluate(EndpointProbeFailure.CONNECTION_REFUSED, allowFallback = false)
        assertEquals(FallbackDecision.FORBIDDEN, result.decision)
        assertEquals(FallbackReason.FALLBACK_DISABLED, result.reason)
    }

    @Test
    fun `downgrade asks the user first when plaintext consent is missing`() {
        val result = evaluate(EndpointProbeFailure.CONNECTION_REFUSED, consent = false)
        assertEquals(FallbackDecision.REQUIRE_USER_CONFIRMATION, result.decision)
        assertEquals(FallbackReason.PLAINTEXT_CONSENT_MISSING, result.reason)
        assertFalse(result.maySwitchToHttp)
    }

    @Test
    fun `authentication failure never downgrades`() {
        val result = evaluate(EndpointProbeFailure.AUTHENTICATION_FAILURE)
        assertEquals(FallbackDecision.FORBIDDEN, result.decision)
        assertEquals(FallbackReason.HTTPS_WORKS_AUTH_FAILED, result.reason)
    }

    @Test
    fun `http 401 and 403 are classified as authentication failures`() {
        assertEquals(
            EndpointProbeFailure.AUTHENTICATION_FAILURE,
            HttpsFallbackPolicy.classifyHttpStatus(401),
        )
        assertEquals(
            EndpointProbeFailure.AUTHENTICATION_FAILURE,
            HttpsFallbackPolicy.classifyHttpStatus(403),
        )
    }

    @Test
    fun `application error statuses never downgrade`() {
        assertEquals(
            EndpointProbeFailure.SERVER_HTTP_ERROR,
            HttpsFallbackPolicy.classifyHttpStatus(404),
        )
        val result = evaluate(EndpointProbeFailure.SERVER_HTTP_ERROR)
        assertEquals(FallbackDecision.FORBIDDEN, result.decision)
        assertEquals(FallbackReason.HTTPS_WORKS_SERVER_ERROR, result.reason)
    }

    @Test
    fun `successful statuses are classified as no failure`() {
        assertEquals(EndpointProbeFailure.NONE, HttpsFallbackPolicy.classifyHttpStatus(200))
        assertEquals(EndpointProbeFailure.NONE, HttpsFallbackPolicy.classifyHttpStatus(302))
        assertEquals(EndpointProbeFailure.NONE, HttpsFallbackPolicy.classifyHttpStatus(399))
    }

    @Test
    fun `dns failure never downgrades`() {
        val result = evaluate(EndpointProbeFailure.DNS_FAILURE)
        assertEquals(FallbackDecision.FORBIDDEN, result.decision)
        assertEquals(FallbackReason.DNS_FAILED, result.reason)
    }

    @Test
    fun `an untrusted certificate requires confirmation and is never bypassed`() {
        val result = evaluate(EndpointProbeFailure.TLS_CERT_UNTRUSTED)
        assertEquals(FallbackDecision.REQUIRE_USER_CONFIRMATION, result.decision)
        assertEquals(FallbackReason.CERTIFICATE_NOT_TRUSTED, result.reason)
        assertFalse(result.maySwitchToHttp)
    }

    @Test
    fun `an untrusted certificate stays forbidden even without fallback consent rules`() {
        val result = evaluate(EndpointProbeFailure.TLS_CERT_UNTRUSTED, allowFallback = false)
        assertEquals(FallbackDecision.REQUIRE_USER_CONFIRMATION, result.decision)
    }

    @Test
    fun `a connect timeout is ambiguous and requires confirmation`() {
        val result = evaluate(EndpointProbeFailure.CONNECT_TIMEOUT)
        assertEquals(FallbackDecision.REQUIRE_USER_CONFIRMATION, result.decision)
        assertEquals(FallbackReason.AMBIGUOUS_FAILURE, result.reason)
    }

    @Test
    fun `a tls handshake failure that is not recognised as plain http requires confirmation`() {
        val result = evaluate(EndpointProbeFailure.TLS_HANDSHAKE_FAILURE)
        assertEquals(FallbackDecision.REQUIRE_USER_CONFIRMATION, result.decision)
    }

    @Test
    fun `unknown failures require confirmation`() {
        val result = evaluate(EndpointProbeFailure.UNKNOWN_NETWORK_ERROR)
        assertEquals(FallbackDecision.REQUIRE_USER_CONFIRMATION, result.decision)
        assertEquals(FallbackReason.UNKNOWN_FAILURE, result.reason)
    }

    @Test
    fun `only connection refused and no route prove that https is absent`() {
        assertTrue(EndpointProbeFailure.CONNECTION_REFUSED.provesHttpsAbsent)
        assertTrue(EndpointProbeFailure.NO_ROUTE_TO_HOST.provesHttpsAbsent)
        assertFalse(EndpointProbeFailure.CONNECT_TIMEOUT.provesHttpsAbsent)
        assertFalse(EndpointProbeFailure.TLS_CERT_UNTRUSTED.provesHttpsAbsent)
        assertFalse(EndpointProbeFailure.TLS_HANDSHAKE_FAILURE.provesHttpsAbsent)
        assertFalse(EndpointProbeFailure.UNKNOWN_NETWORK_ERROR.provesHttpsAbsent)
    }

    @Test
    fun `certificate problems are recognised from typical exception messages`() {
        assertEquals(
            EndpointProbeFailure.TLS_CERT_UNTRUSTED,
            HttpsFallbackPolicy.classifyTlsFailureMessage(
                "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.",
            ),
        )
        assertEquals(
            EndpointProbeFailure.TLS_CERT_UNTRUSTED,
            HttpsFallbackPolicy.classifyTlsFailureMessage(
                "Hostname ha.example.com not verified: certificate: sha256/abc",
            ),
        )
    }

    @Test
    fun `a plain http listener is recognised from the handshake message`() {
        assertEquals(
            EndpointProbeFailure.TLS_HANDSHAKE_FAILURE,
            HttpsFallbackPolicy.classifyTlsFailureMessage(
                "javax.net.ssl.SSLException: Unsupported or unrecognized SSL message",
            ),
        )
    }

    @Test
    fun `unrecognised tls messages stay conservative`() {
        assertEquals(
            EndpointProbeFailure.UNKNOWN_NETWORK_ERROR,
            HttpsFallbackPolicy.classifyTlsFailureMessage("something entirely unexpected"),
        )
        assertEquals(
            EndpointProbeFailure.UNKNOWN_NETWORK_ERROR,
            HttpsFallbackPolicy.classifyTlsFailureMessage(null),
        )
    }
}
