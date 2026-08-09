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
    fun `approved allowlisted packages simulate before fixed apk add command`() {
        val session = RecordingSession()
        val result = RuntimePackageInstaller(RuntimePackageAllowlistPolicy(setOf("git", "python3"))).install(
            session,
            RuntimePackageInstallRequest(listOf("git", "python3")),
            RuntimePackageApproval { CompletableFuture.completedFuture(true) },
        ).toCompletableFuture().join()

        assertEquals(RuntimePackageInstallOutcome.INSTALLED, result.outcome)
        assertEquals(
            listOf(
                RuntimeCommandRequest(
                    executable = "/sbin/apk",
                    arguments = listOf("add", "--simulate", "--no-progress", "git", "python3"),
                    timeoutMillis = 5 * 60_000L,
                ),
                RuntimeCommandRequest(
                    executable = "/sbin/apk",
                    arguments = listOf("add", "--no-progress", "git", "python3"),
                    timeoutMillis = 5 * 60_000L,
                ),
            ),
            session.requests,
        )
    }

    @Test
    fun `failed install simulation never dispatches package mutation`() {
        val session = RecordingSession(results = listOf(RuntimeCommandResult(exitCode = 1)))

        val result = RuntimePackageInstaller(RuntimePackageAllowlistPolicy(setOf("git"))).install(
            session,
            RuntimePackageInstallRequest(listOf("git")),
            RuntimePackageApproval { CompletableFuture.completedFuture(true) },
        ).toCompletableFuture().join()

        assertEquals(RuntimePackageInstallOutcome.PREFLIGHT_FAILED, result.outcome)
        assertEquals(
            listOf("add", "--simulate", "--no-progress", "git"),
            session.lastRequest?.arguments,
        )
        assertEquals(1, session.requests.size)
    }

    @Test
    fun `failed simulation transport never dispatches package mutation`() {
        val session = RecordingSession(failingAttempts = setOf(1))

        val result = RuntimePackageInstaller(RuntimePackageAllowlistPolicy(setOf("git"))).install(
            session,
            RuntimePackageInstallRequest(listOf("git")),
            RuntimePackageApproval { CompletableFuture.completedFuture(true) },
        ).toCompletableFuture().join()

        assertEquals(RuntimePackageInstallOutcome.PREFLIGHT_FAILED, result.outcome)
        assertEquals(listOf("add", "--simulate", "--no-progress", "git"), session.lastRequest?.arguments)
        assertEquals(1, session.requests.size)
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
    fun `approved update simulates before a fixed scoped apk upgrade command`() {
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
        assertEquals(
            listOf(
                RuntimeCommandRequest(
                    executable = "/sbin/apk",
                    arguments = listOf("upgrade", "--simulate", "--no-progress", "git"),
                    timeoutMillis = 5 * 60_000L,
                ),
                RuntimeCommandRequest(
                    executable = "/sbin/apk",
                    arguments = listOf("upgrade", "--no-progress", "git"),
                    timeoutMillis = 5 * 60_000L,
                ),
            ),
            session.requests,
        )
    }

    @Test
    fun `timed out remove simulation never dispatches package deletion`() {
        val session = RecordingSession(results = listOf(RuntimeCommandResult(exitCode = 0, timedOut = true)))

        val result = RuntimePackageMutator(
            RuntimePackageMutationAllowlistPolicy(
                allowedPackages = setOf("git"),
                removablePackages = setOf("git"),
            ),
        ).mutate(
            session = session,
            request = RuntimePackageMutationRequest(RuntimePackageAction.REMOVE, listOf("git")),
            approval = RuntimePackageApproval { CompletableFuture.completedFuture(true) },
        ).toCompletableFuture().join()

        assertEquals(RuntimePackageMutationOutcome.PREFLIGHT_FAILED, result.outcome)
        assertEquals(listOf("del", "--simulate", "--no-progress", "git"), session.lastRequest?.arguments)
        assertEquals(1, session.requests.size)
    }

    private class RecordingSession(
        results: List<RuntimeCommandResult> = emptyList(),
        private val failingAttempts: Set<Int> = emptySet(),
    ) : RuntimeSession {
        override val id: String = "recording"
        override val startedAtEpochMillis: Long = 0
        private val queuedResults = results.toMutableList()
        val requests = mutableListOf<RuntimeCommandRequest>()
        val lastRequest: RuntimeCommandRequest?
            get() = requests.lastOrNull()

        override fun execute(request: RuntimeCommandRequest): CompletionStage<RuntimeCommandResult> {
            requests += request
            if (requests.size in failingAttempts) {
                return CompletableFuture<RuntimeCommandResult>().also {
                    it.completeExceptionally(IllegalStateException("test preflight transport failure"))
                }
            }
            return CompletableFuture.completedFuture(
                if (queuedResults.isEmpty()) RuntimeCommandResult(0) else queuedResults.removeAt(0),
            )
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
