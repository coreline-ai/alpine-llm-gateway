package dev.alpine.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class RuntimePackageInstallerTest {
    @Test
    fun `empty allowlist denies without approval or command dispatch`() {
        val session = RecordingSession()
        var approvalRequested = false
        val result = RuntimePackageInstaller(RuntimePackageAllowlistPolicy(emptySet())).install(
            session,
            RuntimePackageInstallRequest(listOf("git")),
            RuntimePackageApproval {
                approvalRequested = true
                CompletableFuture.completedFuture(true)
            },
        ).toCompletableFuture().join()

        assertEquals(RuntimePackageInstallOutcome.POLICY_DENIED, result.outcome)
        assertEquals(false, approvalRequested)
        assertEquals(null, session.lastRequest)
    }

    @Test
    fun `declined approval does not execute apk`() {
        val session = RecordingSession()
        val result = RuntimePackageInstaller(RuntimePackageAllowlistPolicy(setOf("git"))).install(
            session,
            RuntimePackageInstallRequest(listOf("git")),
            RuntimePackageApproval { CompletableFuture.completedFuture(false) },
        ).toCompletableFuture().join()

        assertEquals(RuntimePackageInstallOutcome.APPROVAL_DECLINED, result.outcome)
        assertEquals(null, session.lastRequest)
    }

    @Test
    fun `approved allowlisted packages dispatch only fixed apk add command`() {
        val session = RecordingSession()
        val result = RuntimePackageInstaller(RuntimePackageAllowlistPolicy(setOf("git", "python3"))).install(
            session,
            RuntimePackageInstallRequest(listOf("git", "python3")),
            RuntimePackageApproval { CompletableFuture.completedFuture(true) },
        ).toCompletableFuture().join()

        assertEquals(RuntimePackageInstallOutcome.INSTALLED, result.outcome)
        assertEquals("/sbin/apk", session.lastRequest?.executable)
        assertEquals(listOf("add", "--no-progress", "git", "python3"), session.lastRequest?.arguments)
    }

    @Test
    fun `shell syntax is rejected as a package name`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimePackageInstallRequest(listOf("git;id"))
        }
    }

    @Test
    fun `remove rejects protected package before approval or command dispatch`() {
        val session = RecordingSession()
        var approvalRequested = false

        val result = RuntimePackageMutator(
            RuntimePackageMutationAllowlistPolicy(
                allowedPackages = setOf("python3", "git"),
                removablePackages = setOf("git"),
            ),
        ).mutate(
            session = session,
            request = RuntimePackageMutationRequest(RuntimePackageAction.REMOVE, listOf("python3")),
            approval = RuntimePackageApproval {
                approvalRequested = true
                CompletableFuture.completedFuture(true)
            },
        ).toCompletableFuture().join()

        assertEquals(RuntimePackageMutationOutcome.POLICY_DENIED, result.outcome)
        assertEquals(false, approvalRequested)
        assertEquals(null, session.lastRequest)
    }

    @Test
    fun `approved update uses a fixed scoped apk upgrade command`() {
        val session = RecordingSession()
        val result = RuntimePackageMutator(
            RuntimePackageMutationAllowlistPolicy(
                allowedPackages = setOf("git"),
                removablePackages = setOf("git"),
            ),
        ).mutate(
            session = session,
            request = RuntimePackageMutationRequest(RuntimePackageAction.UPDATE, listOf("git")),
            approval = RuntimePackageApproval { request ->
                assertEquals(RuntimePackageAction.UPDATE, request.action)
                CompletableFuture.completedFuture(true)
            },
        ).toCompletableFuture().join()

        assertEquals(RuntimePackageMutationOutcome.COMPLETED, result.outcome)
        assertEquals("/sbin/apk", session.lastRequest?.executable)
        assertEquals(listOf("upgrade", "--no-progress", "git"), session.lastRequest?.arguments)
    }

    private class RecordingSession : RuntimeSession {
        override val id: String = "recording"
        override val startedAtEpochMillis: Long = 0
        var lastRequest: RuntimeCommandRequest? = null

        override fun execute(request: RuntimeCommandRequest): CompletionStage<RuntimeCommandResult> {
            lastRequest = request
            return CompletableFuture.completedFuture(RuntimeCommandResult(0))
        }

        override fun openTerminal(request: RuntimeTerminalRequest): CompletionStage<RuntimeTerminalSession> =
            CompletableFuture<RuntimeTerminalSession>().also {
                it.completeExceptionally(UnsupportedOperationException())
            }

        override fun listProcesses(): CompletionStage<List<RuntimeProcessInfo>> =
            CompletableFuture.completedFuture(emptyList())

        override fun health(): CompletionStage<RuntimeHealth> = CompletableFuture.completedFuture(
            RuntimeHealth(true, RuntimeLifecycleState.RUNNING, 0),
        )

        override fun stop(reason: RuntimeStopReason): CompletionStage<Void> =
            CompletableFuture.completedFuture(null)
    }
}
