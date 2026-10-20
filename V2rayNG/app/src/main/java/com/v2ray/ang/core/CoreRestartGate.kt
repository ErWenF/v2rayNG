package com.v2ray.ang.core

/** Fences a replacement start from the restart command through Android's later delivery. */
internal class CoreRestartGate {
    private val gate = Any()
    private var generation = 0L
    private var pending: Long? = null

    fun request(): Long? = synchronized(gate) {
        if (pending != null) return@synchronized null
        (++generation).also { pending = it }
    }

    fun invalidate(): Unit = synchronized(gate) {
        generation++
        pending = null
    }

    fun cancel(ticket: Long): Unit = synchronized(gate) {
        if (pending == ticket) {
            generation++
            pending = null
        }
    }

    fun canDispatch(ticket: Long, previousDestroyed: Boolean): Boolean = synchronized(gate) {
        previousDestroyed && pending == ticket
    }

    /** Untagged explicit/system starts supersede a queued replacement. */
    fun accept(ticket: Long?): Boolean = synchronized(gate) {
        if (ticket != null && pending != ticket) return@synchronized false
        generation++
        pending = null
        true
    }
}
