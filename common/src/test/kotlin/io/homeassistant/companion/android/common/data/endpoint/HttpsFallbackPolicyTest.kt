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
    fun `only a non tls peer, connection refused and no route prove that https is absent`() {
        assertTrue(EndpointProbeFailure.CONNECTION_REFUSED.provesHttpsAbsent)
        assertTrue(EndpointProbeFailure.NO_ROUTE_TO_HOST.provesHttpsAbsent)
        // The peer answered with something that is not a TLS record, which no TLS server does.
        assertTrue(EndpointProbeFailure.TLS_NOT_A_TLS_PEER.provesHttpsAbsent)
        // A handshake that failed mid negotiation can also be a version mismatch with a working
        // HTTPS server, so it must not count as proof.
        assertFalse(EndpointProbeFailure.TLS_HANDSHAKE_FAILURE.provesHttpsAbsent)
        assertFalse(EndpointProbeFailure.CONNECT_TIMEOUT.provesHttpsAbsent)
        assertFalse(EndpointProbeFailure.TLS_CERT_UNTRUSTED.provesHttpsAbsent)
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
            EndpointProbeFailure.TLS_NOT_A_TLS_PEER,
            HttpsFallbackPolicy.classifyTlsFailureMessage(
                "javax.net.ssl.SSLException: Unsupported or unrecognized SSL message",
            ),
        )
        assertEquals(
            EndpointProbeFailure.TLS_NOT_A_TLS_PEER,
            HttpsFallbackPolicy.classifyTlsFailureMessage("first record does not look like a TLS handshake"),
        )
    }

    @Test
    fun `the message a real home assistant server sends is classified as a non tls peer`() {
        // Captured from a real TLS handshake to 117.183.5.29:13481, the endpoint the live TXT record
        // resolves to. It answers plain HTTP with 200 and refuses TLS, and a JVM SSLSocket reports
        // exactly this line, so this is the case the fallback has to get right.
        assertEquals(
            EndpointProbeFailure.TLS_NOT_A_TLS_PEER,
            HttpsFallbackPolicy.classifyTlsFailureMessage("Unsupported or unrecognized SSL message"),
        )
        val evaluation = evaluate(EndpointProbeFailure.TLS_NOT_A_TLS_PEER, consent = true)
        assertEquals(FallbackDecision.AUTO_DOWNGRADE, evaluation.decision)
        assertTrue(evaluation.maySwitchToHttp)
    }

    @Test
    fun `a handshake that fails mid negotiation is never proof on its own`() {
        // The same plain HTTP server can also be reported by a native TLS stack as a version
        // problem, which is indistinguishable from a real HTTPS server that is too old. That case
        // must stay ambiguous, so a message we cannot attribute never becomes a silent downgrade.
        assertEquals(
            EndpointProbeFailure.TLS_HANDSHAKE_FAILURE,
            HttpsFallbackPolicy.classifyTlsFailureMessage("SSLException: WRONG_VERSION_NUMBER"),
        )
        assertEquals(
            FallbackDecision.REQUIRE_USER_CONFIRMATION,
            evaluate(EndpointProbeFailure.TLS_HANDSHAKE_FAILURE, consent = true).decision,
        )
    }

    @Test
    fun `a tls version mismatch stays ambiguous instead of counting as missing https`() {
        // A server that only speaks a TLS version the client no longer offers is still an HTTPS
        // server, so this must never prove that HTTPS is absent.
        assertEquals(
            EndpointProbeFailure.TLS_HANDSHAKE_FAILURE,
            HttpsFallbackPolicy.classifyTlsFailureMessage("javax.net.ssl.SSLException: wrong_version_number"),
        )
        assertFalse(
            HttpsFallbackPolicy.classifyTlsFailureMessage("javax.net.ssl.SSLException: wrong_version_number")
                .provesHttpsAbsent,
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

    // ---------------------------------------------------------------------------------------------
    // Explicit consent. A user who has been told that HTTPS could not be confirmed and who accepts
    // plaintext for this server decides; the rules that protect a *working* HTTPS endpoint still win.
    //
    // Consent is deliberately NOT an input to the ambiguous branches: this policy only classifies
    // evidence, and an ambiguous failure is not evidence that HTTPS is absent. Consent is honoured
    // one layer up, in EndpointResolver's second pass, where the plaintext port is probed before an
    // endpoint is offered or used. These tests hold that separation in place.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an ambiguous failure stays ambiguous no matter what the user consented to`() {
        assertEquals(
            FallbackDecision.REQUIRE_USER_CONFIRMATION,
            evaluate(EndpointProbeFailure.UNKNOWN_NETWORK_ERROR, consent = false).decision,
        )
        assertEquals(
            FallbackDecision.REQUIRE_USER_CONFIRMATION,
            evaluate(EndpointProbeFailure.UNKNOWN_NETWORK_ERROR, consent = true).decision,
        )
        assertEquals(
            FallbackReason.UNKNOWN_FAILURE,
            evaluate(EndpointProbeFailure.UNKNOWN_NETWORK_ERROR, consent = true).reason,
        )
    }

    @Test
    fun `a tls handshake failure stays ambiguous no matter what the user consented to`() {
        assertEquals(
            FallbackDecision.REQUIRE_USER_CONFIRMATION,
            evaluate(EndpointProbeFailure.TLS_HANDSHAKE_FAILURE, consent = false).decision,
        )
        assertEquals(
            FallbackDecision.REQUIRE_USER_CONFIRMATION,
            evaluate(EndpointProbeFailure.TLS_HANDSHAKE_FAILURE, consent = true).decision,
        )
        assertEquals(
            FallbackReason.AMBIGUOUS_FAILURE,
            evaluate(EndpointProbeFailure.CONNECT_TIMEOUT, consent = true).reason,
        )
    }

    @Test
    fun `a provable absence is the only thing consent can turn into a downgrade`() {
        assertEquals(
            FallbackDecision.AUTO_DOWNGRADE,
            evaluate(EndpointProbeFailure.CONNECTION_REFUSED, consent = true).decision,
        )
        assertEquals(
            FallbackDecision.AUTO_DOWNGRADE,
            evaluate(EndpointProbeFailure.TLS_NOT_A_TLS_PEER, consent = true).decision,
        )
    }

    @Test
    fun `an untrusted certificate is never downgraded even with consent`() {
        val result = evaluate(EndpointProbeFailure.TLS_CERT_UNTRUSTED, consent = true)
        assertEquals(FallbackDecision.REQUIRE_USER_CONFIRMATION, result.decision)
        assertEquals(FallbackReason.CERTIFICATE_NOT_TRUSTED, result.reason)
    }

    @Test
    fun `a working https endpoint is never downgraded even with consent`() {
        assertEquals(
            FallbackDecision.FORBIDDEN,
            evaluate(EndpointProbeFailure.AUTHENTICATION_FAILURE, consent = true).decision,
        )
        assertEquals(
            FallbackDecision.FORBIDDEN,
            evaluate(EndpointProbeFailure.SERVER_HTTP_ERROR, consent = true).decision,
        )
    }

    @Test
    fun `a dns failure is never downgraded even with consent`() {
        assertEquals(
            FallbackDecision.FORBIDDEN,
            evaluate(EndpointProbeFailure.DNS_FAILURE, consent = true).decision,
        )
    }

    @Test
    fun `consent does not override a disabled fallback`() {
        assertEquals(
            FallbackDecision.FORBIDDEN,
            evaluate(EndpointProbeFailure.UNKNOWN_NETWORK_ERROR, allowFallback = false, consent = true).decision,
        )
    }
}
