package com.v2ray.ang.handler

import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.util.Po0ApiClient
import com.v2ray.ang.util.parsePo0Tokens
import com.v2ray.ang.util.po0TokenId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Collections
import java.util.UUID

class Po0RunTokensTest {
    private val tokens = listOf("pgnfw_first", "pgnfw_second@0", "pgnfw_third@4", "pgnfw_fourth", "pgnfw_fifth")
    private val appliedReplies = mapOf(
        "pgnfw_first" to response("[\"203.0.113.0/24\"]"),
        "pgnfw_second" to response("[{\"ip\":\"203.0.113.0/24\",\"slot\":0}]"),
        "pgnfw_third" to response("[{\"ip\":\"203.0.113.0/24\",\"slot\":4}]"),
        "pgnfw_fourth" to response("[\"203.0.113.0/24\"]"),
        "pgnfw_fifth" to response("[\"203.0.113.0/24\"]"),
    )

    @Test
    fun batchQueryReadsEveryMixedInputCredentialWithoutAdding() = runBlocking {
        val input = "pgnfw_first，https://124.221.69.228/api/firewall/pgnfw_second/add?slot=0," +
            "https://124.221.69.228/api/firewall/pgnfw_third/add?slot=4；pgnfw_fourth\npgnfw_fifth"
        val script = ApiScript { request -> 200 to appliedReplies.getValue(request.url.pathSegments[2]) }
        val results = po0RunTokens(script.api, parsePo0Tokens(input), queryOnly = true)

        assertEquals(tokens.map(::po0TokenId), results.map { it.tokenId })
        assertTrue(results.all { it.state == Po0ResultState.APPLIED && !it.added })
        assertEquals(listOf(null, 0, 4, null, null), results.map { it.requestedSlot })
        assertEquals(tokens.size, script.requests.size)
        tokens.forEach { token ->
            val requests = script.requestsFor(token)
            assertEquals(listOf("GET"), requests.map { it.method })
            assertEquals(null, requests.single().url.query)
        }
        val status = po0RunStatus(results, now = 123L)
        assertEquals(5, status.successCount)
        assertEquals(5, status.totalCount)
        assertFalse(status.error)
    }

    @Test
    fun batchWhitelistAddsEveryMissingCredentialUsingItsOwnSlot() = runBlocking {
        val script = ApiScript { request ->
            200 to if (request.method == "GET") response("[]") else appliedReplies.getValue(request.url.pathSegments[2])
        }
        val results = po0RunTokens(script.api, tokens)

        assertEquals(tokens.map(::po0TokenId), results.map { it.tokenId })
        assertTrue(results.all { it.state == Po0ResultState.APPLIED && it.added })
        assertEquals(10, script.requests.size)
        tokens.forEach { token ->
            val requests = script.requestsFor(token)
            assertEquals(listOf("GET", "POST"), requests.map { it.method })
            assertEquals(null, requests.first().url.query)
            assertEquals("/api/firewall/${token.substringBefore('@')}/add", requests.last().url.encodedPath)
        }
        assertEquals(listOf(null, "slot=0", "slot=4", null, null), tokens.map { script.requestsFor(it).last().url.query })
        assertFalse(po0RunStatus(results).error)
    }

    @Test
    fun rejectedAndMalformedQueriesDoNotPreventOtherCredentialsFromCompleting() = runBlocking {
        val script = ApiScript { request ->
            when (request.url.pathSegments[2]) {
                "pgnfw_first" -> 403 to "rejected"
                "pgnfw_second" -> 200 to "{}"
                else -> 200 to appliedReplies.getValue(request.url.pathSegments[2])
            }
        }
        val results = po0RunTokens(script.api, tokens, queryOnly = true)

        assertEquals(tokens.map(::po0TokenId), results.map { it.tokenId })
        assertEquals(listOf(Po0ResultState.REJECTED, Po0ResultState.ERROR, Po0ResultState.APPLIED,
            Po0ResultState.APPLIED, Po0ResultState.APPLIED), results.map { it.state })
        assertEquals(tokens.size, script.requests.size)
        tokens.forEach { assertEquals(listOf("GET"), script.requestsFor(it).map { request -> request.method }) }
        val status = po0RunStatus(results)
        assertEquals(3, status.successCount)
        assertEquals(5, status.totalCount)
        assertTrue(status.error)
    }

    @Test
    fun failedQueryAndAddDoNotPreventOtherCredentialsFromBeingWhitelisted() = runBlocking {
        val script = ApiScript { request ->
            val token = request.url.pathSegments[2]
            when {
                token == "pgnfw_first" -> 403 to "rejected"
                token == "pgnfw_second" && request.method == "POST" -> 403 to "rejected"
                token == "pgnfw_fourth" || request.method == "POST" -> 200 to appliedReplies.getValue(token)
                else -> 200 to response("[]")
            }
        }
        val results = po0RunTokens(script.api, tokens)

        assertEquals(tokens.map(::po0TokenId), results.map { it.tokenId })
        assertEquals(listOf(Po0ResultState.REJECTED, Po0ResultState.REJECTED, Po0ResultState.APPLIED,
            Po0ResultState.APPLIED, Po0ResultState.APPLIED), results.map { it.state })
        assertEquals(listOf(false, false, true, false, true), results.map { it.added })
        assertEquals(listOf("GET"), script.requestsFor("pgnfw_first").map { it.method })
        assertEquals(listOf("GET", "POST"), script.requestsFor("pgnfw_second").map { it.method })
        assertEquals(listOf("GET", "POST"), script.requestsFor("pgnfw_third").map { it.method })
        assertEquals(listOf("GET"), script.requestsFor("pgnfw_fourth").map { it.method })
        assertEquals(listOf("GET", "POST"), script.requestsFor("pgnfw_fifth").map { it.method })
        val status = po0RunStatus(results)
        assertEquals(3, status.successCount)
        assertEquals(5, status.totalCount)
        assertTrue(status.error)
    }

    @Test
    fun legacyPoliciesForTheSameServerAreBlockedWithoutRequestsWhileOtherServersWork() = runBlocking {
        listOf(false, true).forEach { queryOnly ->
            val script = ApiScript { 200 to response("[\"203.0.113.0/24\"]") }
            val duplicated = listOf("pgnfw_shared", "pgnfw_shared@0", "pgnfw_shared@1", "pgnfw_other")
            val results = po0RunTokens(script.api, duplicated, queryOnly)
            assertEquals(duplicated.map(::po0TokenId), results.map { it.tokenId })
            assertEquals(listOf(Po0ResultState.CONFLICT, Po0ResultState.CONFLICT, Po0ResultState.CONFLICT,
                Po0ResultState.APPLIED), results.map { it.state })
            assertTrue(results.take(3).all { it.localConflict && !it.slotConflict && !it.added })
            assertEquals(listOf(null, 0, 1), results.take(3).map { it.requestedSlot })
            assertEquals(listOf("pgnfw_other"), script.requests.map { it.url.pathSegments[2] })
            assertEquals(listOf("GET"), script.requests.map { it.method })
        }
    }

    @Test
    fun unexpectedPerCredentialExceptionsAreIsolatedWithoutFailingHealthySiblings() = runBlocking {
        val script = ApiScript { request ->
            if (request.url.pathSegments[2] == "pgnfw_first") throw IllegalStateException("sensitive URL must stay private")
            200 to appliedReplies.getValue(request.url.pathSegments[2])
        }
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val results = po0RunTokens(script.api, tokens, queryOnly = true, reportFailure = { id, _ -> failures.add(id) })
        assertEquals(listOf(Po0ResultState.ERROR) + List(4) { Po0ResultState.APPLIED }, results.map { it.state })
        assertEquals(listOf(po0TokenId("pgnfw_first")), failures)
        assertEquals(5, script.requests.size)
        assertFalse(results.toString().contains("sensitive"))
    }

    @Test
    fun cancellationIsPropagatedInsteadOfBecomingACredentialError() {
        val script = ApiScript { throw CancellationException("cancelled network generation") }
        assertThrows(CancellationException::class.java) {
            runBlocking { po0RunTokens(script.api, tokens, reportFailure = { _, _ -> throw AssertionError("must propagate") }) }
        }
    }

    @Test
    fun completedBatchesHaveDistinctRevisionsEvenWhenTheWallClockIsUnchangedOrMovesBackwards() {
        val first = po0RunStatus(emptyList(), now = 123L)
        val second = po0RunStatus(emptyList(), now = 123L)
        val third = po0RunStatus(emptyList(), now = 100L)
        assertEquals(123L, second.checkedAt)
        assertEquals(100L, third.checkedAt)
        assertNotEquals(first.revision, second.revision)
        assertNotEquals(second.revision, third.revision)
        listOf(first, second, third).forEach { UUID.fromString(it.revision) }
    }

    private fun response(whitelist: String): String =
        """{"currentIp":"203.0.113.8","enabled":true,"whitelist":$whitelist}"""

    private class ApiScript(reply: (Request) -> Pair<Int, String>) {
        private val recordedRequests = Collections.synchronizedList(mutableListOf<Request>())
        val requests: List<Request> get() = synchronized(recordedRequests) { recordedRequests.toList() }
        private val http = mock<OkHttpClient>()
        val api = Po0ApiClient(http) { _, _, _ -> }

        fun requestsFor(token: String): List<Request> = requests.filter { it.url.pathSegments[2] == token.substringBefore('@') }

        init {
            whenever(http.newCall(any())).thenAnswer { invocation ->
                val request = invocation.getArgument<Request>(0)
                recordedRequests.add(request)
                val call = mock<Call>()
                doAnswer { callbackInvocation ->
                    val callback = callbackInvocation.getArgument<Callback>(0)
                    val response = reply(request)
                    callback.onResponse(call, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(response.first).message("test").body(response.second.toResponseBody()).build())
                    null
                }.whenever(call).enqueue(any())
                call
            }
        }
    }
}
