package com.v2ray.ang.service

import android.content.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class Po0WhitelistRuntimeTest {
    @Test
    fun restartTransitionKeepsTheSameManagerUntilTheReplacementStops() = Fixture().use { f ->
        val oldRun = f.attach()
        val transition = f.attach()
        val manager = f.managers.single()
        f.detach(oldRun, proxyStopped = true)
        verify(manager, never()).close()
        val replacement = f.attach()
        f.detach(transition)
        assertEquals(1, f.managers.size)
        verify(manager, times(1)).start()
        verify(manager, never()).close()
        Po0WhitelistRuntime.refreshNow()
        verify(manager, times(2)).requestCheck()
        f.detach(replacement)
        verify(manager, times(1)).close()
    }

    @Test
    fun cancelledOrFailedRestartReleasesTheLastTemporaryOwner() {
        for (oldTeardownFinished in listOf(false, true)) {
            Fixture().use { f ->
                val oldRun = f.attach()
                val transition = f.attach()
                val manager = f.managers.single()
                if (oldTeardownFinished) f.detach(oldRun)
                f.detach(transition)
                if (!oldTeardownFinished) {
                    verify(manager, never()).close()
                    f.detach(oldRun)
                }
                verify(manager, times(1)).close()
                f.detach(transition)
                verify(manager, times(1)).close()
                f.attach()
                assertEquals(2, f.managers.size)
            }
        }
    }

    @Test
    fun oldFinallyCannotReleaseTheExplicitReplacementOrANewerTransition() = Fixture().use { f ->
        val oldRun = f.attach()
        val oldTransition = f.attach()
        val manager = f.managers.single()
        f.detach(oldRun)
        val explicitRun = f.attach()
        val nextTransition = f.attach()
        f.detach(oldTransition)
        f.detach(oldTransition)
        f.detach(explicitRun)
        verify(manager, never()).close()
        val nextRun = f.attach()
        f.detach(nextTransition)
        assertEquals(1, f.managers.size)
        verify(manager, never()).close()
        f.detach(nextRun)
        verify(manager, times(1)).close()
    }

    @Test
    fun endingATransitionKeepsAnIndependentMaintenanceOwner() = Fixture().use { f ->
        val independent = f.attach()
        val oldRun = f.attach()
        val transition = f.attach()
        val manager = f.managers.single()
        f.detach(oldRun, proxyStopped = true)
        verify(manager).requestCheck()
        f.detach(transition)
        verify(manager, never()).close()
        f.detach(independent)
        verify(manager, times(1)).close()
    }

    private class Fixture : AutoCloseable {
        private val context = mock<Context>().also { whenever(it.applicationContext).thenReturn(it) }
        private val construction = mockConstruction(Po0WhitelistManager::class.java)
        private val owners = mutableSetOf<Any>()
        val managers get() = construction.constructed()

        fun attach(): Any = Any().also {
            owners.add(it)
            Po0WhitelistRuntime.attach(it, context)
        }

        fun detach(owner: Any, proxyStopped: Boolean = false) {
            Po0WhitelistRuntime.detach(owner, proxyStopped)
            owners.remove(owner)
        }

        override fun close() {
            try {
                owners.toList().forEach { detach(it) }
            } finally {
                construction.close()
            }
        }
    }
}
