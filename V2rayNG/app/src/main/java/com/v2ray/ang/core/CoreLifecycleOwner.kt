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

/** One start attempt owns its work and exactly one teardown, including a partially failed start. */
internal class CoreLifecycleOwner(context: CoroutineContext = Dispatchers.IO) {
    val scope = CoroutineScope(context + SupervisorJob())
    private val gate = Any()
    private var teardown: Job? = null
    private val stopRequested = CompletableDeferred<Job>()

    val isStopping: Boolean get() = synchronized(gate) {
        teardown != null && scope.coroutineContext[Job]!!.isCompleted != true
    }

    fun stop(cleanup: suspend () -> Unit): Job {
        val job = synchronized(gate) {
            teardown ?: scope.launch(start = CoroutineStart.LAZY) {
                try {
                    cleanup()
                } finally {
                    scope.cancel()
                }
            }.also {
                teardown = it
                stopRequested.complete(it)
            }
        }
        job.start()
        return job
    }

    /** Wait for service-owned cleanup to request teardown and for the real native cleanup to finish. */
    suspend fun awaitStopped() {
        stopRequested.await().join()
        scope.coroutineContext[Job]!!.join()
    }
}
