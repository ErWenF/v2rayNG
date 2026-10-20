package com.v2ray.ang.service

import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0TokenResult

/**
 * One session owns these deadlines, using elapsed time only. One timer wakes the batch scheduler;
 * a failed server never changes a healthy server's deadline.
 */
internal class Po0CheckSchedule {
    private data class Attempt(val completedAt: Long, val failures: Int, val result: Po0TokenResult)
    private val attempts = mutableMapOf<String, Attempt>()

    fun invalidate(id: String) { attempts.remove(id) }

    fun retain(ids: Set<String>) { attempts.keys.retainAll(ids) }

    fun configurationFailure(id: String): Po0TokenResult? = attempts[id]?.result?.takeIf {
        !it.localConflict && it.state in setOf(Po0ResultState.REJECTED, Po0ResultState.DISABLED, Po0ResultState.CONFLICT)
    }

    /** A new access network can fix transport errors, but cannot fix rejected credentials. */
    fun inheritConfigurationFailures(previous: Po0CheckSchedule, ids: Set<String>) {
        ids.forEach { id ->
            if (previous.configurationFailure(id) != null) previous.attempts[id]?.let { attempts[id] = it }
        }
    }

    fun record(id: String, result: Po0TokenResult, now: Long) {
        val failures = if (result.state == Po0ResultState.APPLIED) 0 else (attempts[id]?.failures ?: 0) + 1
        attempts[id] = Attempt(now, failures.coerceAtMost(10), result)
    }

    fun dueAt(id: String, delay: (Int) -> Long): Long {
        val attempt = attempts[id] ?: return 0
        val period = if (attempt.result.state in setOf(Po0ResultState.REJECTED, Po0ResultState.DISABLED, Po0ResultState.CONFLICT)) {
            maxOf(delay(attempt.failures), 1_800_000L)
        } else delay(attempt.failures)
        return po0Deadline(attempt.completedAt, maxOf(period, attempt.result.retryAfterMillis))
    }

    fun shouldCheck(id: String, now: Long, event: Boolean, delay: (Int) -> Long): Boolean {
        val attempt = attempts[id] ?: return true
        // A proxy event reuses recent success and cannot bypass a server's failure/cooldown.
        return if (event && attempt.result.state == Po0ResultState.APPLIED) {
            now >= po0Deadline(attempt.completedAt, 20_000L)
        } else now >= dueAt(id, delay)
    }
}

internal fun po0Deadline(now: Long, delayMillis: Long): Long =
    if (delayMillis.coerceAtLeast(0) > Long.MAX_VALUE - now.coerceAtLeast(0)) Long.MAX_VALUE
    else now.coerceAtLeast(0) + delayMillis.coerceAtLeast(0)

/** Reordered/repeated framework lists are not a new route or local address. */
internal fun po0LinkFingerprint(interfaceName: String?, addresses: List<String>, routes: List<String>): String =
    listOf(interfaceName.orEmpty(), addresses.distinct().sorted().joinToString("|"), routes.distinct().sorted().joinToString("|"))
        .joinToString("\n")
