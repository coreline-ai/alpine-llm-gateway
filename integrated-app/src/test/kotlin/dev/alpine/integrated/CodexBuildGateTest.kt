package dev.alpine.integrated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexBuildGateTest {
    @Test
    fun `feature and rollback builds have an unambiguous package contract`() {
        when {
            BuildConfig.CODEX_APP_SERVER_ENABLED -> {
                assertFalse(BuildConfig.CODEX_APP_SERVER_ROLLBACK_BUILD)
                assertEquals(CODEX_DEBUG_PACKAGE, BuildConfig.APPLICATION_ID)
            }

            BuildConfig.CODEX_APP_SERVER_ROLLBACK_BUILD -> {
                assertFalse(BuildConfig.CODEX_APP_SERVER_ENABLED)
                assertEquals(CODEX_DEBUG_PACKAGE, BuildConfig.APPLICATION_ID)
            }

            else -> {
                assertFalse(BuildConfig.CODEX_APP_SERVER_ENABLED)
                assertEquals(DEFAULT_DEBUG_PACKAGE, BuildConfig.APPLICATION_ID)
            }
        }
    }

    @Test
    fun `rollback build is feature off by contract`() {
        if (BuildConfig.CODEX_APP_SERVER_ROLLBACK_BUILD) {
            assertFalse(BuildConfig.CODEX_APP_SERVER_ENABLED)
            assertTrue(BuildConfig.APPLICATION_ID.endsWith(".codexdebug"))
        }
    }

    private companion object {
        const val DEFAULT_DEBUG_PACKAGE = "dev.alpine.integrated.debug"
        const val CODEX_DEBUG_PACKAGE = "dev.alpine.integrated.codexdebug"
    }
}
