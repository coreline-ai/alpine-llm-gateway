package dev.alpine.llm.demo

import android.content.Intent
import android.view.View
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.llm.demo.data.ProviderProfileStore
import dev.alpine.llm.demo.model.ProviderType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderGuiInstrumentedTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext

    @Before
    fun clearProfiles() {
        context.getSharedPreferences(
            ProviderProfileStore.FILE_NAME,
            android.content.Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @After
    fun cleanupProfiles() {
        clearProfiles()
    }

    @Test
    fun mainScreenStartsWithProviderManagementAvailable() {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        instrumentation.waitForIdleSync()

        assertNotNull(activity.findViewById<View>(R.id.manageProvidersButton))
        assertEquals(
            context.getString(R.string.no_connected_provider),
            activity.findViewById<android.widget.TextView>(R.id.statusText).text.toString(),
        )
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun geminiFormShowsProjectFieldAndSavesValidatedProfile() {
        val activity = instrumentation.startActivitySync(
            Intent(context, ProviderEditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(
                    ProviderEditActivity.EXTRA_PROVIDER_TYPE,
                    ProviderType.GEMINI.wireName,
                ),
        ) as ProviderEditActivity
        instrumentation.waitForIdleSync()

        assertEquals(
            View.VISIBLE,
            activity.findViewById<View>(R.id.googleProjectInput).visibility,
        )
        assertEquals(
            View.GONE,
            activity.findViewById<View>(R.id.anthropicBetaInput).visibility,
        )
        assertTrue(
            activity.findViewById<EditText>(R.id.inferenceEndpointInput)
                .text.toString()
                .contains("{model}"),
        )

        instrumentation.runOnMainSync {
            activity.findViewById<EditText>(R.id.authorizationEndpointInput)
                .setText("https://identity.example.test/oauth/authorize")
            activity.findViewById<EditText>(R.id.tokenEndpointInput)
                .setText("https://identity.example.test/oauth/token")
            activity.findViewById<EditText>(R.id.clientIdInput)
                .setText("android-public-client")
            activity.findViewById<EditText>(R.id.modelInput)
                .setText("gemini-test")
            activity.findViewById<View>(R.id.saveButton).performClick()
        }
        instrumentation.waitForIdleSync()

        val saved = ProviderProfileStore(context).load().single()
        assertEquals(ProviderType.GEMINI, saved.type)
        assertEquals("gemini-test", saved.model)
        assertTrue(saved.validationErrors().isEmpty())
        val storedRaw = context.getSharedPreferences(
            ProviderProfileStore.FILE_NAME,
            android.content.Context.MODE_PRIVATE,
        ).all.toString()
        assertFalse(storedRaw.contains("access_token"))
        assertFalse(storedRaw.contains("refresh_token"))
        assertFalse(storedRaw.contains("client_secret"))
    }

    @Test
    fun eachProviderFormShowsOnlyItsAdditionalField() {
        val expectations = listOf(
            Triple(ProviderType.ANTHROPIC, View.VISIBLE, View.GONE),
            Triple(ProviderType.GEMINI, View.GONE, View.VISIBLE),
            Triple(ProviderType.OPENAI_COMPATIBLE, View.GONE, View.GONE),
        )

        expectations.forEach { (type, anthropicVisibility, googleVisibility) ->
            val activity = instrumentation.startActivitySync(
                Intent(context, ProviderEditActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ProviderEditActivity.EXTRA_PROVIDER_TYPE, type.wireName),
            ) as ProviderEditActivity
            instrumentation.waitForIdleSync()

            assertEquals(
                anthropicVisibility,
                activity.findViewById<View>(R.id.anthropicBetaInput).visibility,
            )
            assertEquals(
                googleVisibility,
                activity.findViewById<View>(R.id.googleProjectInput).visibility,
            )
            assertEquals(
                type.inferenceEndpointPlaceholder,
                activity.findViewById<EditText>(R.id.inferenceEndpointInput).text.toString(),
            )
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
