package com.v2ray.ang.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** One start attempt owns its work and exactly one teardown, including a partially failed start. */
internal class CoreLifecycleOwner(context: CoroutineContext = Dispatchers.IO) {
    val scope = CoroutineScope(context + SupervisorJob())
    private val gate = Any()
    private var setup: Job? = null
    private var teardown: Job? = null
    private val stopRequested = CompletableDeferred<Job>()

    val isStopRequested: Boolean get() = synchronized(gate) { teardown != null }

    val isStopping: Boolean get() = synchronized(gate) {
        teardown != null && scope.coroutineContext[Job]!!.isCompleted != true
    }

    /** A duplicate command cannot create another preflight, core, tunnel or route setup. */
    fun start(work: suspend () -> Unit): Boolean {
        val job = synchronized(gate) {
            if (setup != null || teardown != null) return false
            scope.launch(start = CoroutineStart.LAZY) { work() }.also { setup = it }
        }
        job.start()
        return true
    }

    fun stop(cleanup: suspend () -> Unit): Job {
        val job = synchronized(gate) {
            teardown ?: scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // A cancelled blocking native/root call may still finish acquiring resources.
                    // Its attempt must finish before cleanup releases any of those dependencies.
                    val setupJob = setup
                    if (setupJob !== coroutineContext[Job]) setupJob?.join()
                    cleanup()
                } finally {
                    scope.cancel()
                }
            }.also {
                teardown = it
                stopRequested.complete(it)
            }
        }
        synchronized(gate) { setup }?.cancel()
        job.start()
        return job
    }

    /** Wait for service-owned cleanup to request teardown and for the real native cleanup to finish. */
    suspend fun awaitStopped() {
        stopRequested.await().join()
        scope.coroutineContext[Job]!!.join()
    }
}
