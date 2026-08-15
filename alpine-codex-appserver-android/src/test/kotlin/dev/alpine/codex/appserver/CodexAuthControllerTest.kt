package dev.alpine.codex.appserver

import dev.alpine.codex.appserver.protocol.CodexRpcNotification
import java.net.URI
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexAuthControllerTest {
    @Test
    fun `matching successful login notification refreshes to signed in`() = runTest {
        val account = FakeAccountApi()
        CodexAuthController(account, this).use { controller ->
            val login = controller.startBrowserLogin()
            assertEquals("login-1", login.loginId)
            assertTrue(controller.state.value is CodexAuthState.BrowserPending)

            account.accountState = CodexAccountState.CHATGPT
            account.emitLogin(login.loginId, success = true)
            runCurrent()

            assertEquals(CodexAuthState.SignedIn, controller.state.value)

            account.emitLogin(login.loginId, success = true)
            runCurrent()
            assertEquals(CodexAuthState.SignedIn, controller.state.value)
        }
    }

    @Test
    fun `terminal client failure disarms pending login timeout`() = runTest {
        val account = FakeAccountApi()
        CodexAuthController(account, this, loginTimeoutMs = 100).use { controller ->
            controller.startBrowserLogin()
            account.emitClientFailure(CodexAppServerErrorCode.PROTOCOL_INVALID)
            runCurrent()

            assertEquals(
                CodexAuthState.Failed(CodexAppServerErrorCode.PROTOCOL_INVALID),
                controller.state.value,
            )

            advanceTimeBy(100)
            advanceUntilIdle()
            assertTrue(account.cancelled.isEmpty())
            assertEquals(
                CodexAuthState.Failed(CodexAppServerErrorCode.PROTOCOL_INVALID),
                controller.state.value,
            )
        }
    }

    @Test
    fun `different login notification is ignored and failure is closed`() = runTest {
        val account = FakeAccountApi()
        CodexAuthController(account, this).use { controller ->
            controller.startBrowserLogin()
            account.emitLogin("different", success = true)
            runCurrent()
            assertTrue(controller.state.value is CodexAuthState.BrowserPending)

            account.emitLogin("login-1", success = false)
            runCurrent()
            assertEquals(
                CodexAuthState.Failed(CodexAppServerErrorCode.LOGIN_FAILED),
                controller.state.value,
            )
        }
    }

    @Test
    fun `nullable login id is accepted but malformed used fields fail closed`() = runTest {
        val nullableIdAccount = FakeAccountApi()
        CodexAuthController(nullableIdAccount, this).use { controller ->
            controller.startBrowserLogin()
            nullableIdAccount.accountState = CodexAccountState.CHATGPT
            nullableIdAccount.emitLoginParams(
                JSONObject().put("loginId", JSONObject.NULL).put("success", true),
            )
            runCurrent()
            assertEquals(CodexAuthState.SignedIn, controller.state.value)
        }

        listOf(
            JSONObject().put("loginId", "login-1").put("success", "true"),
            JSONObject().put("loginId", 1).put("success", true),
            JSONObject().put("loginId", "bad id").put("success", true),
        ).forEach { malformed ->
            val account = FakeAccountApi()
            CodexAuthController(account, this, loginTimeoutMs = 100).use { controller ->
                controller.startBrowserLogin()
                account.emitLoginParams(malformed)
                runCurrent()
                assertEquals(
                    CodexAuthState.Failed(CodexAppServerErrorCode.PROTOCOL_INVALID),
                    controller.state.value,
                )
                advanceTimeBy(100)
                advanceUntilIdle()
                assertTrue(account.cancelled.isEmpty())
            }
        }
    }

    @Test
    fun `starting a second login explicitly cancels the first`() = runTest {
        val account = FakeAccountApi()
        CodexAuthController(account, this).use { controller ->
            controller.startBrowserLogin()
            controller.startDeviceCodeLogin()

            assertEquals(listOf("login-1"), account.cancelled)
            assertTrue(controller.state.value is CodexAuthState.DeviceCodePending)
        }
    }

    @Test
    fun `refresh during activity recreation preserves pending login without replay`() = runTest {
        val account = FakeAccountApi()
        CodexAuthController(account, this).use { controller ->
            controller.startBrowserLogin()

            controller.refresh()
            assertTrue(controller.state.value is CodexAuthState.BrowserPending)
            assertEquals(1, account.browserLoginCalls)

            account.accountState = CodexAccountState.CHATGPT
            controller.refresh()
            assertEquals(CodexAuthState.SignedIn, controller.state.value)
            assertEquals(1, account.browserLoginCalls)
        }
    }

    @Test
    fun `pending login timeout cancels once and closes with stable code`() = runTest {
        val account = FakeAccountApi()
        CodexAuthController(account, this, loginTimeoutMs = 100).use { controller ->
            controller.startBrowserLogin()
            advanceTimeBy(100)
            advanceUntilIdle()

            assertEquals(listOf("login-1"), account.cancelled)
            assertEquals(
                CodexAuthState.Failed(CodexAppServerErrorCode.REQUEST_TIMEOUT),
                controller.state.value,
            )
        }
    }

    @Test
    fun `browser launch failure cancels pending login once with stable code`() = runTest {
        val account = FakeAccountApi()
        CodexAuthController(account, this, loginTimeoutMs = 100).use { controller ->
            controller.startBrowserLogin()
            controller.failPendingLogin(CodexAppServerErrorCode.BROWSER_UNAVAILABLE)

            assertEquals(listOf("login-1"), account.cancelled)
            assertEquals(
                CodexAuthState.Failed(CodexAppServerErrorCode.BROWSER_UNAVAILABLE),
                controller.state.value,
            )

            advanceTimeBy(100)
            advanceUntilIdle()
            assertEquals(listOf("login-1"), account.cancelled)
            assertEquals(
                CodexAuthState.Failed(CodexAppServerErrorCode.BROWSER_UNAVAILABLE),
                controller.state.value,
            )
        }
    }

    @Test
    fun `logout verifies account state after server response`() = runTest {
        val account = FakeAccountApi().apply { accountState = CodexAccountState.CHATGPT }
        CodexAuthController(account, this).use { controller ->
            controller.refresh()
            assertEquals(CodexAuthState.SignedIn, controller.state.value)

            account.stateAfterLogout = CodexAccountState.SIGNED_OUT
            controller.logout()

            assertEquals(1, account.logoutCalls)
            assertEquals(CodexAuthState.SignedOut, controller.state.value)
        }
    }

    private class FakeAccountApi : CodexAccountApi {
        private val mutableNotifications = MutableSharedFlow<CodexRpcNotification>(
            extraBufferCapacity = 8,
        )
        override val notifications: SharedFlow<CodexRpcNotification> = mutableNotifications
        var accountState = CodexAccountState.SIGNED_OUT
        var stateAfterLogout = CodexAccountState.SIGNED_OUT
        var logoutCalls = 0
        var browserLoginCalls = 0
        val cancelled = mutableListOf<String>()

        override suspend fun accountState(): CodexAccountState = accountState

        override suspend fun startBrowserLogin(): CodexLoginStart.Browser {
            browserLoginCalls += 1
            return CodexLoginStart.Browser(
                loginId = "login-1",
                authorizationUri = URI("https://chatgpt.com/auth"),
            )
        }

        override suspend fun startDeviceCodeLogin() = CodexLoginStart.DeviceCode(
            loginId = "login-2",
            verificationUri = URI("https://auth.openai.com/device"),
            userCode = "SAFE-CODE",
        )

        override suspend fun cancelLogin(loginId: String) {
            cancelled += loginId
        }

        override suspend fun logout() {
            logoutCalls += 1
            accountState = stateAfterLogout
        }

        fun emitLogin(loginId: String, success: Boolean) {
            emitLoginParams(JSONObject().put("loginId", loginId).put("success", success))
        }

        fun emitLoginParams(params: JSONObject) {
            check(
                mutableNotifications.tryEmit(
                    CodexRpcNotification(
                        method = "account/login/completed",
                        params = params,
                    ),
                ),
            )
        }

        fun emitClientFailure(code: CodexAppServerErrorCode) {
            check(
                mutableNotifications.tryEmit(
                    CodexRpcNotification(
                        method = "client/failure",
                        params = JSONObject(),
                        clientFailureCode = code,
                    ),
                ),
            )
        }
    }
}
