package dev.alpine.runtime.android

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidFactoryBoundaryTest {
    @Test
    fun `context is confined to factory create method`() {
        val contextMethods = AndroidAlpineRuntimeFactory::class.java.methods.filter { method ->
            method.parameterTypes.any { Context::class.java.isAssignableFrom(it) }
        }

        assertEquals(listOf("create"), contextMethods.map { it.name }.distinct())
    }
}
