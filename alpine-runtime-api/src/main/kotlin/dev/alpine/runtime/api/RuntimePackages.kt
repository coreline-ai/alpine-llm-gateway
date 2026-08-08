package dev.alpine.runtime.api

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

private val RUNTIME_PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+_.-]{0,127}")

private fun isRuntimePackageName(value: String): Boolean = RUNTIME_PACKAGE_NAME.matches(value)

/** A validated package request. Raw shell fragments are deliberately not accepted. */
data class RuntimePackageInstallRequest @JvmOverloads constructor(
    val packages: List<String>,
    val timeoutMillis: Long = 5 * 60_000L,
) {
    init {
        require(packages.isNotEmpty()) { "at least one package is required" }
        require(packages.size <= MAX_PACKAGES_PER_REQUEST) { "too many packages" }
        require(packages.distinct().size == packages.size) { "duplicate packages are not allowed" }
        require(packages.all(::isRuntimePackageName)) { "invalid package name" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    companion object {
        const val MAX_PACKAGES_PER_REQUEST = 32
    }
}

/** Fixed apk operations exposed by the host. No arbitrary apk subcommands are accepted. */
enum class RuntimePackageAction {
    INSTALL,
    REMOVE,
    UPDATE,
}

/**
 * A validated destructive package operation. UPDATE is restricted to explicit exact package
 * names rather than a whole-system `apk upgrade`, preventing an unexpected Runtime-wide change.
 */
data class RuntimePackageMutationRequest @JvmOverloads constructor(
    val action: RuntimePackageAction,
    val packages: List<String>,
    val timeoutMillis: Long = 5 * 60_000L,
) {
    init {
        require(action != RuntimePackageAction.INSTALL) { "use RuntimePackageInstallRequest for install" }
        require(packages.isNotEmpty()) { "at least one package is required" }
        require(packages.size <= RuntimePackageInstallRequest.MAX_PACKAGES_PER_REQUEST) { "too many packages" }
        require(packages.distinct().size == packages.size) { "duplicate packages are not allowed" }
        require(packages.all(::isRuntimePackageName)) { "invalid package name" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
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
data class RuntimePackageApprovalRequest @JvmOverloads constructor(
    val packages: List<String>,
    val action: RuntimePackageAction = RuntimePackageAction.INSTALL,
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

/** Exact-name policy for intentional package mutation. Remove has a narrower allowlist. */
class RuntimePackageMutationAllowlistPolicy(
    allowedPackages: Set<String>,
    removablePackages: Set<String>,
) {
    private val allowedPackages = allowedPackages.toSet()
    private val removablePackages = removablePackages.toSet()

    fun evaluate(request: RuntimePackageMutationRequest): RuntimePackagePolicyDecision = when (request.action) {
        RuntimePackageAction.UPDATE -> if (request.packages.all(allowedPackages::contains)) {
            RuntimePackagePolicyDecision.ALLOW
        } else {
            RuntimePackagePolicyDecision.DENY
        }
        RuntimePackageAction.REMOVE -> if (request.packages.all(removablePackages::contains) &&
            request.packages.all(allowedPackages::contains)
        ) {
            RuntimePackagePolicyDecision.ALLOW
        } else {
            RuntimePackagePolicyDecision.DENY
        }
        RuntimePackageAction.INSTALL -> RuntimePackagePolicyDecision.DENY
    }
}

enum class RuntimePackageMutationOutcome {
    COMPLETED,
    POLICY_DENIED,
    APPROVAL_DECLINED,
}

data class RuntimePackageMutationResult(
    val action: RuntimePackageAction,
    val outcome: RuntimePackageMutationOutcome,
    val commandResult: RuntimeCommandResult? = null,
)

/**
 * A display-only package record supplied by the Runtime pack or the consuming application.
 *
 * Values describe one resolved package archive, not the full dependency transaction.  They must
 * therefore be labelled with [snapshotId] in UI and must never be used as an installation
 * admission decision.  Allowlist policy remains the source of truth for mutations.
 */
data class RuntimePackageMetadata(
    val packageName: String,
    val resolvedPackageName: String = packageName,
    val version: String,
    val licenseExpression: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val repository: String,
    val architecture: String,
    val snapshotId: String,
    val sourceUrl: String,
) {
    init {
        require(isRuntimePackageName(packageName)) {
            "invalid package name"
        }
        require(isRuntimePackageName(resolvedPackageName)) {
            "invalid resolved package name"
        }
        require(version.isNotBlank()) { "version must not be blank" }
        require(licenseExpression.isNotBlank()) { "licenseExpression must not be blank" }
        require(downloadBytes >= 0) { "downloadBytes must not be negative" }
        require(installedBytes >= 0) { "installedBytes must not be negative" }
        require(repository.isNotBlank()) { "repository must not be blank" }
        require(architecture.isNotBlank()) { "architecture must not be blank" }
        require(snapshotId.isNotBlank()) { "snapshotId must not be blank" }
        require(sourceUrl.startsWith("https://")) { "sourceUrl must use https" }
    }
}

/**
 * A bounded, package-archive-only estimate for a user selection.
 *
 * [downloadBytes] and [installedBytes] exclude current dependency resolution, package-index
 * downloads, cache and filesystem overhead.  A missing entry deliberately prevents callers from
 * presenting a complete estimate.
 */
data class RuntimePackageEstimate(
    val metadata: List<RuntimePackageMetadata>,
    val missingPackageNames: List<String>,
    val downloadBytes: Long,
    val installedBytes: Long,
) {
    val isComplete: Boolean
        get() = missingPackageNames.isEmpty()
}

/**
 * Immutable package metadata lookup.  It is deliberately separate from mutation policy: a
 * catalog is informative only, while [RuntimePackageAllowlistPolicy] authorizes execution.
 */
class RuntimePackageCatalog(entries: Collection<RuntimePackageMetadata>) {
    private val byRequestedName = entries.associateBy(RuntimePackageMetadata::packageName)

    init {
        require(byRequestedName.size == entries.size) { "duplicate package metadata" }
    }

    fun metadataFor(packageName: String): RuntimePackageMetadata? = byRequestedName[packageName]

    fun estimate(packageNames: Collection<String>): RuntimePackageEstimate {
        val requestedNames = packageNames.distinct()
        val metadata = requestedNames.mapNotNull(byRequestedName::get)
        val missing = requestedNames.filterNot(byRequestedName::containsKey)
        return RuntimePackageEstimate(
            metadata = metadata,
            missingPackageNames = missing,
            downloadBytes = metadata.sumBytes(RuntimePackageMetadata::downloadBytes),
            installedBytes = metadata.sumBytes(RuntimePackageMetadata::installedBytes),
        )
    }

    private fun List<RuntimePackageMetadata>.sumBytes(
        selector: (RuntimePackageMetadata) -> Long,
    ): Long = fold(0L) { total, metadata -> Math.addExact(total, selector(metadata)) }
}

/**
 * UI-neutral package mutator. The only dispatched commands are exact `apk del` and scoped
 * `apk upgrade` argv forms after both allowlist policy and user approval pass.
 */
class RuntimePackageMutator(
    private val policy: RuntimePackageMutationAllowlistPolicy,
) {
    fun mutate(
        session: RuntimeSession,
        request: RuntimePackageMutationRequest,
        approval: RuntimePackageApproval,
    ): CompletionStage<RuntimePackageMutationResult> {
        if (runCatching { policy.evaluate(request) }.getOrDefault(RuntimePackagePolicyDecision.DENY) !=
            RuntimePackagePolicyDecision.ALLOW
        ) {
            return CompletableFuture.completedFuture(
                RuntimePackageMutationResult(request.action, RuntimePackageMutationOutcome.POLICY_DENIED),
            )
        }
        val approvalStage = runCatching {
            approval.requestApproval(RuntimePackageApprovalRequest(request.packages.toList(), request.action))
        }.getOrElse {
            return failedStage(RuntimeOperationException(RuntimeErrorCode.INTERNAL_ERROR))
        }
        return approvalStage.thenCompose { approved ->
            if (!approved) {
                CompletableFuture.completedFuture(
                    RuntimePackageMutationResult(request.action, RuntimePackageMutationOutcome.APPROVAL_DECLINED),
                )
            } else {
                session.execute(
                    RuntimeCommandRequest(
                        executable = "/sbin/apk",
                        arguments = listOf(apkSubcommand(request.action), "--no-progress") + request.packages,
                        timeoutMillis = request.timeoutMillis,
                    ),
                ).thenCompose { command ->
                    if (command.exitCode != 0 || command.timedOut) {
                        failedStage(RuntimeOperationException(RuntimeErrorCode.COMMAND_FAILED))
                    } else {
                        CompletableFuture.completedFuture(
                            RuntimePackageMutationResult(
                                action = request.action,
                                outcome = RuntimePackageMutationOutcome.COMPLETED,
                                commandResult = command,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun apkSubcommand(action: RuntimePackageAction): String = when (action) {
        RuntimePackageAction.REMOVE -> "del"
        RuntimePackageAction.UPDATE -> "upgrade"
        RuntimePackageAction.INSTALL -> error("install must use RuntimePackageInstaller")
    }

    private fun <T> failedStage(error: Throwable): CompletionStage<T> =
        CompletableFuture<T>().also { it.completeExceptionally(error) }
}
