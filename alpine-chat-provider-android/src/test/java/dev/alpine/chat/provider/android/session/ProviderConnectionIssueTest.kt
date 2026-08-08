package dev.alpine.chat.provider.android.session

import dev.alpine.llm.OAuthException
import dev.alpine.llm.OAuthFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderConnectionIssueTest {
    @Test
    fun everyOAuthFailureKindMapsToStableRedactedCode() {
        val expected = mapOf(
            OAuthFailureKind.USER_DENIED to "AUTH_USER_DENIED",
            OAuthFailureKind.CALLBACK_TIMEOUT to "AUTH_CALLBACK_TIMEOUT",
            OAuthFailureKind.STATE_MISMATCH to "AUTH_STATE_REJECTED",
            OAuthFailureKind.TRANSACTION_EXPIRED to "AUTH_SESSION_EXPIRED",
            OAuthFailureKind.INVALID_GRANT to "AUTH_CREDENTIAL_REJECTED",
            OAuthFailureKind.STORAGE_INVALIDATED to "AUTH_STORAGE_INVALIDATED",
            OAuthFailureKind.STORAGE_FAILURE to "AUTH_STORAGE_FAILURE",
            OAuthFailureKind.PROVIDER_ERROR to "AUTH_PROVIDER_REJECTED",
            OAuthFailureKind.NETWORK to "AUTH_NETWORK",
            OAuthFailureKind.PROTOCOL to "AUTH_PROTOCOL",
            OAuthFailureKind.CONFIGURATION to "AUTH_CONFIGURATION",
        )

        expected.forEach { (kind, code) ->
            val issue = ProviderConnectionIssue.from(
                OAuthException("secret-provider-response", kind),
            )
            assertEquals(code, issue.code)
            assertFalse(issue.visibleText().contains("secret-provider-response"))
        }
    }

    @Test
    fun unknownFailureDoesNotExposeThrowableMessage() {
        val issue = ProviderConnectionIssue.from(
            IllegalStateException("private endpoint and response body"),
        )

        assertEquals("AUTH_UNKNOWN", issue.code)
        assertFalse(issue.visibleText().contains("private endpoint"))
    }

    @Test
    fun lifecycleRecoveryUsesFixedRedactedGuidance() {
        val interrupted = ProviderConnectionIssue.authorizationInterrupted()
        val expired = ProviderConnectionIssue.authorizationExpired()

        assertEquals("AUTH_FLOW_INTERRUPTED", interrupted.code)
        assertEquals("AUTH_SESSION_EXPIRED", expired.code)
        assertFalse(interrupted.visibleText().contains("state="))
        assertFalse(expired.visibleText().contains("verifier"))
    }

    private fun ProviderConnectionIssue.visibleText(): String =
        listOf(code, message, nextAction).joinToString(" ")
}
