package dev.alpine.runtime.bridge

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Conservative policy for recovering a Gateway that was already started by the user or a
 * requested Alpine chat preparation. It never starts a Gateway after a cold process launch and
 * it never replays a chat request or terminal command.
 */
data class AlpineLlmBridgeRecoveryConfiguration @JvmOverloads constructor(
    val healthIntervalMillis: Long = 30_000L,
    val maxAutomaticRestarts: Int = 2,
    val initialBackoffMillis: Long = 1_000L,
    val maxBackoffMillis: Long = 8_000L,
) {
    init {
        require(healthIntervalMillis > 0) { "healthIntervalMillis must be positive" }
        require(maxAutomaticRestarts in 0..8) { "maxAutomaticRestarts must be between 0 and 8" }
        require(initialBackoffMillis > 0) { "initialBackoffMillis must be positive" }
        require(maxBackoffMillis >= initialBackoffMillis) {
            "maxBackoffMillis must be at least initialBackoffMillis"
        }
    }
}

enum class AlpineLlmBridgeRecoveryMode {
    /** No automatic recovery is armed. A user can start the Gateway explicitly. */
    STOPPED,

    /** A known-running Gateway is being periodically checked. */
    MONITORING,

    /** A failed health check is being recovered within the configured retry budget. */
    RECOVERING,

    /** The bounded recovery budget has been consumed. Manual restart is required. */
    EXHAUSTED,
}

data class AlpineLlmBridgeRecoveryState(
    val mode: AlpineLlmBridgeRecoveryMode = AlpineLlmBridgeRecoveryMode.STOPPED,
    val consecutiveRestarts: Int = 0,
    val errorCode: LlmBridgeErrorCode? = null,
)

fun interface AlpineLlmBridgeRecoveryListener {
    fun onStateChanged(state: AlpineLlmBridgeRecoveryState)
}

/**
 * App-neutral, single-owner health supervisor for [AlpineLlmBridgeController].
 *
 * Call [startMonitoring] only once an already-authorized lifecycle owner has successfully
 * started the Gateway. [stopMonitoring] is deliberately synchronous: an explicit Stop revokes
 * the current generation before an in-flight health callback can schedule another restart.
 */
class AlpineLlmBridgeRecoverySupervisor internal constructor(
    private val configuration: AlpineLlmBridgeRecoveryConfiguration,
    private val healthCheck: () -> CompletionStage<AlpineLlmBridgeHealth>,
    private val restartGateway: () -> CompletionStage<AlpineLlmBridgeHealth>,
    private val listener: AlpineLlmBridgeRecoveryListener,
    private val scheduler: ScheduledExecutorService,
) : AutoCloseable {
    @JvmOverloads
    constructor(
        healthCheck: () -> CompletionStage<AlpineLlmBridgeHealth>,
        restartGateway: () -> CompletionStage<AlpineLlmBridgeHealth>,
        configuration: AlpineLlmBridgeRecoveryConfiguration = AlpineLlmBridgeRecoveryConfiguration(),
        listener: AlpineLlmBridgeRecoveryListener = AlpineLlmBridgeRecoveryListener { },
    ) : this(
        configuration = configuration,
        healthCheck = healthCheck,
        restartGateway = restartGateway,
        listener = listener,
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "alpine-gateway-health").apply { isDaemon = true }
        },
    )

    private val lock = Any()
    private var state = AlpineLlmBridgeRecoveryState()
    private var generation = 0L
    private var scheduledCheck: ScheduledFuture<*>? = null
    private var closed = false

    fun currentState(): AlpineLlmBridgeRecoveryState = synchronized(lock) { state }

    /** Arms monitoring for the current running owner and immediately verifies its health. */
    fun startMonitoring() {
        val next = synchronized(lock) {
            check(!closed) { "recovery supervisor is closed" }
            generation += 1
            scheduledCheck?.cancel(false)
            scheduledCheck = null
            state = AlpineLlmBridgeRecoveryState(mode = AlpineLlmBridgeRecoveryMode.MONITORING)
            state
        }
        emit(next)
        scheduleCheck(delayMillis = 0L, expectedGeneration = currentGeneration())
    }

    /**
     * Revokes monitoring immediately. It must be called before an explicit Stop, owner swap, or
     * application close; it never invokes the lifecycle owner's stop action itself.
     */
    fun stopMonitoring() {
        val next = synchronized(lock) {
            if (closed) return
            generation += 1
            scheduledCheck?.cancel(false)
            scheduledCheck = null
            state = AlpineLlmBridgeRecoveryState(mode = AlpineLlmBridgeRecoveryMode.STOPPED)
            state
        }
        emit(next)
    }

    /** Schedules a health check without resetting the retry budget. */
    fun checkNow() {
        val expected = synchronized(lock) {
            if (closed || state.mode != AlpineLlmBridgeRecoveryMode.MONITORING) return
            generation
        }
        scheduleCheck(delayMillis = 0L, expectedGeneration = expected)
    }

    override fun close() {
        stopMonitoring()
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        scheduler.shutdownNow()
    }

    private fun scheduleCheck(delayMillis: Long, expectedGeneration: Long) {
        val scheduled = runCatching {
            scheduler.schedule(
                { runHealthCheck(expectedGeneration) },
                delayMillis,
                TimeUnit.MILLISECONDS,
            )
        }.getOrNull() ?: return
        synchronized(lock) {
            if (!isMonitoringLocked(expectedGeneration)) {
                scheduled.cancel(false)
            } else {
                scheduledCheck?.cancel(false)
                scheduledCheck = scheduled
            }
        }
    }

    private fun runHealthCheck(expectedGeneration: Long) {
        if (!isMonitoring(expectedGeneration)) return
        val future = try {
            healthCheck()
        } catch (error: Throwable) {
            failedHealth(error)
        }
        future.whenComplete { health, error ->
            executeSafely {
                completeHealthCheck(expectedGeneration, health, error)
            }
        }
    }

    private fun completeHealthCheck(
        expectedGeneration: Long,
        health: AlpineLlmBridgeHealth?,
        error: Throwable?,
    ) {
        if (!isMonitoring(expectedGeneration)) return
        if (error == null && health?.healthy == true) {
            val next = synchronized(lock) {
                if (!isMonitoringLocked(expectedGeneration)) return
                state = AlpineLlmBridgeRecoveryState(mode = AlpineLlmBridgeRecoveryMode.MONITORING)
                state
            }
            emit(next)
            scheduleCheck(configuration.healthIntervalMillis, expectedGeneration)
            return
        }
        beginRecovery(expectedGeneration, health?.errorCode ?: LlmBridgeErrorCode.GATEWAY_HEALTH_FAILED)
    }

    private fun beginRecovery(expectedGeneration: Long, errorCode: LlmBridgeErrorCode) {
        val restartAttempt = synchronized(lock) {
            if (!isMonitoringLocked(expectedGeneration)) return
            val nextAttempt = state.consecutiveRestarts + 1
            if (nextAttempt > configuration.maxAutomaticRestarts) {
                state = AlpineLlmBridgeRecoveryState(
                    mode = AlpineLlmBridgeRecoveryMode.EXHAUSTED,
                    consecutiveRestarts = state.consecutiveRestarts,
                    errorCode = errorCode,
                )
                null
            } else {
                state = AlpineLlmBridgeRecoveryState(
                    mode = AlpineLlmBridgeRecoveryMode.RECOVERING,
                    consecutiveRestarts = nextAttempt,
                    errorCode = errorCode,
                )
                nextAttempt
            }
        }
        val snapshot = currentState()
        emit(snapshot)
        if (restartAttempt == null) return
        val delay = retryDelay(restartAttempt)
        runCatching {
            scheduler.schedule(
                { runRestart(expectedGeneration) },
                delay,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun runRestart(expectedGeneration: Long) {
        if (!isRecovering(expectedGeneration)) return
        val future = try {
            restartGateway()
        } catch (error: Throwable) {
            failedHealth(error)
        }
        future.whenComplete { health, error ->
            executeSafely {
                completeRestart(expectedGeneration, health, error)
            }
        }
    }

    private fun completeRestart(
        expectedGeneration: Long,
        health: AlpineLlmBridgeHealth?,
        error: Throwable?,
    ) {
        if (!isRecovering(expectedGeneration)) return
        if (error == null && health?.healthy == true) {
            val next = synchronized(lock) {
                if (!isRecoveringLocked(expectedGeneration)) return
                state = AlpineLlmBridgeRecoveryState(mode = AlpineLlmBridgeRecoveryMode.MONITORING)
                state
            }
            emit(next)
            scheduleCheck(configuration.healthIntervalMillis, expectedGeneration)
            return
        }
        val errorCode = health?.errorCode ?: LlmBridgeErrorCode.GATEWAY_HEALTH_FAILED
        val next = synchronized(lock) {
            if (!isRecoveringLocked(expectedGeneration)) return
            if (state.consecutiveRestarts >= configuration.maxAutomaticRestarts) {
                state = state.copy(mode = AlpineLlmBridgeRecoveryMode.EXHAUSTED, errorCode = errorCode)
            } else {
                state = state.copy(mode = AlpineLlmBridgeRecoveryMode.MONITORING, errorCode = errorCode)
            }
            state
        }
        emit(next)
        if (next.mode == AlpineLlmBridgeRecoveryMode.MONITORING) {
            // The next check immediately consumes the next bounded recovery attempt.
            scheduleCheck(0L, expectedGeneration)
        }
    }

    private fun retryDelay(attempt: Int): Long {
        var delay = configuration.initialBackoffMillis
        repeat((attempt - 1).coerceAtLeast(0)) {
            delay = (delay * 2).coerceAtMost(configuration.maxBackoffMillis)
        }
        return delay
    }

    private fun currentGeneration(): Long = synchronized(lock) { generation }

    private fun isMonitoring(expectedGeneration: Long): Boolean = synchronized(lock) {
        isMonitoringLocked(expectedGeneration)
    }

    private fun isMonitoringLocked(expectedGeneration: Long): Boolean =
        !closed && generation == expectedGeneration && state.mode == AlpineLlmBridgeRecoveryMode.MONITORING

    private fun isRecovering(expectedGeneration: Long): Boolean = synchronized(lock) {
        isRecoveringLocked(expectedGeneration)
    }

    private fun isRecoveringLocked(expectedGeneration: Long): Boolean =
        !closed && generation == expectedGeneration && state.mode == AlpineLlmBridgeRecoveryMode.RECOVERING

    private fun executeSafely(block: () -> Unit) {
        runCatching { scheduler.execute(block) }
    }

    private fun emit(value: AlpineLlmBridgeRecoveryState) {
        runCatching { listener.onStateChanged(value) }
    }

    private fun failedHealth(error: Throwable): CompletionStage<AlpineLlmBridgeHealth> =
        CompletableFuture<AlpineLlmBridgeHealth>().also { it.completeExceptionally(error) }
}
