package com.v2ray.ang.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes native handovers with teardown without making Android callbacks wait for native I/O. */
internal class CoreHandoverGate {
    private val stateGate = Any()
    private val mutation = Mutex()
    private var generation = 0L
    private var active = false

    fun activate(): Unit = synchronized(stateGate) {
        generation++
        active = true
    }

    fun invalidate(): Unit = synchronized(stateGate) {
        generation++
        active = false
    }

    fun ticket(): Long? = synchronized(stateGate) { generation.takeIf { active } }

    private fun isCurrent(ticket: Long): Boolean = synchronized(stateGate) { active && generation == ticket }

    suspend fun reload(
        ticket: Long,
        canReload: () -> Boolean,
        stop: suspend () -> Unit,
        start: suspend (checkCurrent: () -> Unit) -> Unit,
    ): Boolean = mutation.withLock {
        if (!isCurrent(ticket) || !canReload()) return@withLock false
        currentCoroutineContext().ensureActive()
        stop()
        currentCoroutineContext().ensureActive()
        if (!isCurrent(ticket)) return@withLock false
        // Configuration/DNS building can outlive a stop, so the caller also checks before native start.
        start {
            if (!isCurrent(ticket)) throw CancellationException("Core handover superseded")
        }
        isCurrent(ticket)
    }

    suspend fun teardown(stop: suspend () -> Unit) = mutation.withLock { stop() }
}
