package dev.alpine.chat.provider.android.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSaveActionTest {
    @Test
    fun loginIntentIsExplicit() {
        assertTrue(ProviderSaveAction.SAVE_AND_LOGIN.requestLogin)
        assertFalse(ProviderSaveAction.SAVE_FOR_LATER.requestLogin)
    }
}
