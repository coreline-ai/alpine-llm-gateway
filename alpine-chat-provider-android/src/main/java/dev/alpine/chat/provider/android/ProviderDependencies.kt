package dev.alpine.chat.provider.android

import android.content.Context
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.provider.android.session.ProviderSessionFactory
import dev.alpine.chat.provider.android.model.ProviderProfile

/**
 * Small application-level dependency boundary.
 *
 * Production always uses [ProviderSessionFactory]. Instrumentation can install a
 * credential-free fake from the target process and must reset it after each test.
 */
object ProviderDependencies {
    @Volatile
    private var testSessionFactory:
        ((Context, ProviderProfile) -> ChatCompletionSession)? = null

    fun createSession(
        context: Context,
        profile: ProviderProfile,
    ): ChatCompletionSession =
        testSessionFactory?.invoke(context.applicationContext, profile)
            ?: ProviderSessionFactory.create(context.applicationContext, profile)

    /** Test-process hook. Production hosts must leave this set to `null`. */
    fun installSessionFactoryForTests(
        factory: ((Context, ProviderProfile) -> ChatCompletionSession)?,
    ) {
        testSessionFactory = factory
    }
}
