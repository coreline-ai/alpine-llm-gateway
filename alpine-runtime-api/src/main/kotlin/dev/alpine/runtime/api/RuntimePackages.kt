package dev.alpine.runtime.api

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** A validated package request. Raw shell fragments are deliberately not accepted. */
data class RuntimePackageInstallRequest @JvmOverloads constructor(
    val packages: List<String>,
    val timeoutMillis: Long = 5 * 60_000L,
) {
    init {
        require(packages.isNotEmpty()) { "at least one package is required" }
        require(packages.size <= MAX_PACKAGES_PER_REQUEST) { "too many packages" }
        require(packages.distinct().size == packages.size) { "duplicate packages are not allowed" }
        require(packages.all(PACKAGE_NAME::matches)) { "invalid package name" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    companion object {
        const val MAX_PACKAGES_PER_REQUEST = 32
        private val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+_.-]{0,127}")
    }
}

enum class RuntimePackagePolicyDecision {
    ALLOW,
    DENY,
}

fun interface RuntimePackagePolicy {
    fun evaluate(request: RuntimePackageInstallRequest): RuntimePackagePolicyDecision
}

/** Exact-name allowlist. An empty allowlist is fail-closed. */
class RuntimePackageAllowlistPolicy(
    allowedPackages: Set<String>,
) : RuntimePackagePolicy {
    private val allowedPackages = allowedPackages.toSet()

    override fun evaluate(request: RuntimePackageInstallRequest): RuntimePackagePolicyDecision =
        if (request.packages.all(allowedPackages::contains)) {
            RuntimePackagePolicyDecision.ALLOW
        } else {
            RuntimePackagePolicyDecision.DENY
        }
}

/** Safe information a host may render in its own confirmation UI. */
data class RuntimePackageApprovalRequest(
    val packages: List<String>,
)

fun interface RuntimePackageApproval {
    fun requestApproval(request: RuntimePackageApprovalRequest): CompletionStage<Boolean>
}

enum class RuntimePackageInstallOutcome {
    INSTALLED,
    POLICY_DENIED,
    APPROVAL_DECLINED,
}

data class RuntimePackageInstallResult(
    val outcome: RuntimePackageInstallOutcome,
    val commandResult: RuntimeCommandResult? = null,
)

/**
 * UI-neutral package installer that can only dispatch the fixed apk-add command.
 * Policy and explicit host approval both run before the RuntimeSession is touched.
 */
class RuntimePackageInstaller(
    private val policy: RuntimePackagePolicy,
) {
    fun install(
        session: RuntimeSession,
        request: RuntimePackageInstallRequest,
        approval: RuntimePackageApproval,
    ): CompletionStage<RuntimePackageInstallResult> {
        val decision = runCatching { policy.evaluate(request) }
            .getOrDefault(RuntimePackagePolicyDecision.DENY)
        if (decision != RuntimePackagePolicyDecision.ALLOW) {
            return CompletableFuture.completedFuture(
                RuntimePackageInstallResult(RuntimePackageInstallOutcome.POLICY_DENIED),
            )
        }

        val approvalStage = runCatching {
            approval.requestApproval(RuntimePackageApprovalRequest(request.packages.toList()))
        }.getOrElse {
            return failedStage(RuntimeOperationException(RuntimeErrorCode.INTERNAL_ERROR))
        }
        return approvalStage.thenCompose { approved ->
            if (!approved) {
                CompletableFuture.completedFuture(
                    RuntimePackageInstallResult(RuntimePackageInstallOutcome.APPROVAL_DECLINED),
                )
            } else {
                session.execute(
                    RuntimeCommandRequest(
                        executable = "/sbin/apk",
                        arguments = listOf("add", "--no-progress") + request.packages,
                        timeoutMillis = request.timeoutMillis,
                    ),
                ).thenCompose { command ->
                    if (command.exitCode != 0 || command.timedOut) {
                        failedStage(RuntimeOperationException(RuntimeErrorCode.COMMAND_FAILED))
                    } else {
                        CompletableFuture.completedFuture(
                            RuntimePackageInstallResult(
                                RuntimePackageInstallOutcome.INSTALLED,
                                command,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun <T> failedStage(error: Throwable): CompletionStage<T> =
        CompletableFuture<T>().also { it.completeExceptionally(error) }
}
