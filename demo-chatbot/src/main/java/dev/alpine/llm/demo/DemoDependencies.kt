package dev.alpine.llm.demo

import android.content.Context
import dev.alpine.llm.demo.llm.ChatCompletionSession
import dev.alpine.llm.demo.llm.ProviderSessionFactory
import dev.alpine.llm.demo.model.ProviderProfile

/**
 * Small application-level dependency boundary.
 *
 * Production always uses [ProviderSessionFactory]. Instrumentation can install a
 * credential-free fake from the target process and must reset it after each test.
 */
object DemoDependencies {
    @Volatile
    private var testSessionFactory:
        ((Context, ProviderProfile) -> ChatCompletionSession)? = null

    fun createSession(
        context: Context,
        profile: ProviderProfile,
    ): ChatCompletionSession =
        testSessionFactory?.invoke(context.applicationContext, profile)
            ?: ProviderSessionFactory.create(context.applicationContext, profile)

    internal fun installSessionFactoryForTests(
        factory: ((Context, ProviderProfile) -> ChatCompletionSession)?,
    ) {
        testSessionFactory = factory
    }
}
