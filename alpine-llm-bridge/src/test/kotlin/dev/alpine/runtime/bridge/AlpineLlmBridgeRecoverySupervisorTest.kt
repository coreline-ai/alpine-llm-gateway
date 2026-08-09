package dev.alpine.runtime.bridge

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineLlmBridgeRecoverySupervisorTest {
    @Test
    fun `failed health restarts a previously started gateway and returns to monitoring`() {
        val restarts = AtomicInteger()
        val recovered = CountDownLatch(1)
        val healthy = AtomicInteger(0)
        val supervisor = AlpineLlmBridgeRecoverySupervisor(
            healthCheck = { CompletableFuture.completedFuture(health(healthy.get() == 1)) },
            restartGateway = {
                restarts.incrementAndGet()
                healthy.set(1)
                CompletableFuture.completedFuture(health(true))
            },
            configuration = policy(maxRestarts = 1),
            listener = AlpineLlmBridgeRecoveryListener { state ->
                if (state.mode == AlpineLlmBridgeRecoveryMode.MONITORING && restarts.get() == 1) {
                    recovered.countDown()
                }
            },
        )

        supervisor.startMonitoring()

        assertTrue("automatic recovery did not complete", recovered.await(2, TimeUnit.SECONDS))
        assertEquals(1, restarts.get())
        assertEquals(AlpineLlmBridgeRecoveryMode.MONITORING, supervisor.currentState().mode)
        supervisor.close()
    }

    @Test
    fun `explicit stop revokes an in-flight health generation before it can restart`() {
        val pendingHealth = CompletableFuture<AlpineLlmBridgeHealth>()
        val checked = CountDownLatch(1)
        val restarts = AtomicInteger()
        val supervisor = AlpineLlmBridgeRecoverySupervisor(
            healthCheck = {
                checked.countDown()
                pendingHealth
            },
            restartGateway = {
                restarts.incrementAndGet()
                CompletableFuture.completedFuture(health(true))
            },
            configuration = policy(maxRestarts = 2),
        )

        supervisor.startMonitoring()
        assertTrue("health check did not start", checked.await(2, TimeUnit.SECONDS))
        supervisor.stopMonitoring()
        pendingHealth.complete(health(false))
        Thread.sleep(100)

        assertEquals(0, restarts.get())
        assertEquals(AlpineLlmBridgeRecoveryMode.STOPPED, supervisor.currentState().mode)
        supervisor.close()
    }

    @Test
    fun `recovery budget exhausts without unbounded restart loop`() {
        val restarts = AtomicInteger()
        val exhausted = CountDownLatch(1)
        val supervisor = AlpineLlmBridgeRecoverySupervisor(
            healthCheck = { CompletableFuture.completedFuture(health(false)) },
            restartGateway = {
                restarts.incrementAndGet()
                CompletableFuture.completedFuture(health(false))
            },
            configuration = policy(maxRestarts = 2),
            listener = AlpineLlmBridgeRecoveryListener { state ->
                if (state.mode == AlpineLlmBridgeRecoveryMode.EXHAUSTED) exhausted.countDown()
            },
        )

        supervisor.startMonitoring()

        assertTrue("recovery budget did not exhaust", exhausted.await(2, TimeUnit.SECONDS))
        assertEquals(2, restarts.get())
        assertEquals(AlpineLlmBridgeRecoveryMode.EXHAUSTED, supervisor.currentState().mode)
        assertFalse(supervisor.currentState().errorCode == null)
        supervisor.close()
    }

    @Test
    fun `explicit stop revokes lease for an already executing restart without a follow-up check`() {
        val healthChecks = AtomicInteger()
        val restartStarted = CountDownLatch(1)
        val releaseRestart = CountDownLatch(1)
        val leaseWasActiveAfterStop = AtomicBoolean(true)
        val supervisor = AlpineLlmBridgeRecoverySupervisor.withRecoveryLease(
            healthCheck = {
                healthChecks.incrementAndGet()
                CompletableFuture.completedFuture(health(false))
            },
            restartGateway = { lease ->
                restartStarted.countDown()
                releaseRestart.await(2, TimeUnit.SECONDS)
                leaseWasActiveAfterStop.set(lease.isActive())
                CompletableFuture.completedFuture(health(true))
            },
            configuration = policy(maxRestarts = 1),
        )

        supervisor.startMonitoring()
        assertTrue("restart did not start", restartStarted.await(2, TimeUnit.SECONDS))
        supervisor.stopMonitoring()
        releaseRestart.countDown()
        Thread.sleep(100)

        assertFalse("restart callback retained authority after explicit Stop", leaseWasActiveAfterStop.get())
        assertEquals(1, healthChecks.get())
        assertEquals(AlpineLlmBridgeRecoveryMode.STOPPED, supervisor.currentState().mode)
        supervisor.close()
    }

    private fun policy(maxRestarts: Int) = AlpineLlmBridgeRecoveryConfiguration(
        healthIntervalMillis = 60_000L,
        maxAutomaticRestarts = maxRestarts,
        initialBackoffMillis = 1L,
        maxBackoffMillis = 4L,
    )

    private fun health(healthy: Boolean) = AlpineLlmBridgeHealth(
        healthy = healthy,
        lifecycle = if (healthy) LlmBridgeLifecycleState.RUNNING else LlmBridgeLifecycleState.FAILED,
        errorCode = if (healthy) null else LlmBridgeErrorCode.GATEWAY_HEALTH_FAILED,
    )
}
