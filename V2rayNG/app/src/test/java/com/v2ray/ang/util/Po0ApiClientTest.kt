package com.v2ray.ang.util

import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0WhitelistEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest

class Po0ApiClientTest {

    @Test
    fun tokenParsingPreservesInvalidItemsAndFirstOccurrence() {
        val raw = "pgnfw_first, pgnfw_second\npgnfw_first;invalid|pgnfw_third、pgnfw_fourth@0"
        assertEquals(listOf("pgnfw_first", "pgnfw_second", "invalid", "pgnfw_third", "pgnfw_fourth@0"), parsePo0Tokens(raw))
        assertFalse(validatePo0Tokens(raw))
        assertTrue(validatePo0Tokens("\n pgnfw_first, pgnfw_second@4 "))
        assertTrue(validatePo0Tokens(""))
    }

    @Test
    fun tokenAndOfficialUrlListsSupportEverySeparatorAndPreserveFirstOccurrence() {
        val values = listOf(
            "https://124.221.69.228/api/firewall/pgnfw_first",
            "https://124.221.69.228/api/firewall/pgnfw_second/add?slot=2",
            "pgnfw_third@4",
            "pgnfw_first",
        )
        listOf(",", "，", ";", "；", "、", "|", "\n", " \t ").forEach { separator ->
            val input = values.joinToString(separator)
            assertEquals(listOf("pgnfw_first", "pgnfw_second@2", "pgnfw_third@4"), parsePo0Tokens(input))
            assertTrue(validatePo0Tokens(input))
        }
    }

    @Test
    fun mixedUrlListsKeepInvalidItemsInsteadOfIgnoringThem() {
        listOf(
            "invalid",
            "pgnfw_bad@9",
            "https://evil.example/api/firewall/pgnfw_bad/add",
            "http://124.221.69.228/api/firewall/pgnfw_bad/add",
            "https://124.221.69.228:444/api/firewall/pgnfw_bad/add",
            "https://user@124.221.69.228/api/firewall/pgnfw_bad/add",
            "https://124.221.69.228/api/firewall/pgnfw_bad/add/trailing",
            "https://124.221.69.228/api/firewall/pgnfw_bad/add?slot=0&slot=1",
            "https://124.221.69.228/api/firewall/pgnfw_bad/add?other=1",
            "https://124.221.69.228/api/firewall/pgnfw_bad?slot=0",
            "https://124.221.69.228/api/firewall/pgnfw_bad/add#trailing",
        ).forEach { invalid ->
            val input = "https://124.221.69.228/api/firewall/pgnfw_first,$invalid；pgnfw_last"
            assertEquals(listOf("pgnfw_first", invalid, "pgnfw_last"), parsePo0Tokens(input))
            assertFalse(validatePo0Tokens(input))
        }
    }

    @Test
    fun tokenValidationRejectsBadPrefixBadSlotsAndUrlDelimiters() {
        assertTrue(validatePo0Tokens("pgnfw_ui_test_a"))
        listOf("pgnfw_", "wrong_token", "pgnfw_a@name", "pgnfw_a@5", "pgnfw_a@-1", "pgnfw_a@00",
            "pgnfw_a@@0", "pgnfw_a/add", "pgnfw_a?x=1", "pgnfw_a#x", "pgnfw_a$(command)")
            .forEach { assertFalse(validatePo0Tokens(it), it) }
    }

    @Test
    fun tokenIdentityIsAHashOfTheEntireConfiguration() {
        val token = "pgnfw_private-secret"
        val expected = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, po0TokenId(token))
        assertEquals(16, po0TokenId(token).length)
        assertFalse(po0TokenId(token).contains("pgnfw_"))
        assertNotEquals(po0TokenId(token), po0TokenId("$token@0"))
    }

    @Test
    fun intervalValidationIncludesBothDocumentedBounds() {
        assertTrue(validatePo0IntervalSeconds(1))
        assertTrue(validatePo0IntervalSeconds(3600))
        assertFalse(validatePo0IntervalSeconds(0))
        assertFalse(validatePo0IntervalSeconds(3601))
    }

    @Test
    fun extractsOfficialConfigAndApiUrlsWithoutExecutingThem() {
        val expected = listOf("pgnfw_a@0", "pgnfw_b")
        assertEquals(expected, extractPo0Tokens("# /etc/po0fw.conf\nPO0FW_TOKENS=\"pgnfw_a@0,pgnfw_b\""))
        assertEquals(listOf("pgnfw_a@4"), extractPo0Tokens("https://124.221.69.228/api/firewall/pgnfw_a/add?slot=4"))
        assertEquals(listOf("pgnfw_a"), extractPo0Tokens("https://124.221.69.228/api/firewall/pgnfw_a"))
        assertTrue(validatePo0Tokens("PO0FW_TOKENS='pgnfw_a@0,pgnfw_b'"))
    }

    @Test
    fun extractsAllOfficialInstallerSnippetForms() {
        val snippets = listOf(
            "curl -sSL https://raw.githubusercontent.com/w0ven/po0fw/main/openwrt/install-openwrt.sh -o /tmp/i.sh\nPO0FW_TOKENS=\"pgnfw_a@0,pgnfw_b\" sh /tmp/i.sh",
            "curl -sSL https://raw.githubusercontent.com/w0ven/po0fw/main/install-linux.sh | PO0FW_TOKENS=\"pgnfw_a@0,pgnfw_b\" sh",
            "irm https://raw.githubusercontent.com/w0ven/po0fw/main/windows/install-windows.ps1 -OutFile i.ps1\npowershell -ExecutionPolicy Bypass -File i.ps1 -Tokens \"pgnfw_a@0,pgnfw_b\"",
            "curl -sSL https://po0fw.uuuz.de/po0fw.sh | bash -s -- pgnfw_a@0，pgnfw_b",
            "curl -sSL https://raw.githubusercontent.com/w0ven/po0fw/main/install-linux.sh | PO0FW_TOKENS=\"pgnfw_a@0；pgnfw_b\" sh",
        )
        snippets.forEach { snippet ->
            assertEquals(listOf("pgnfw_a@0", "pgnfw_b"), parsePo0Tokens(snippet))
            assertTrue(validatePo0Tokens(snippet))
        }
    }

    @Test
    fun extractsOfficialCurlAddRequestButRejectsUnexpectedHostsAndArguments() {
        assertEquals(listOf("pgnfw_a@0"), parsePo0Tokens("curl -4 -X POST 'https://124.221.69.228/api/firewall/pgnfw_a/add?slot=0'"))
        listOf(
            "https://evil.example/api/firewall/pgnfw_a/add",
            "https://124.221.69.228.evil.example/api/firewall/pgnfw_a/add",
            "https://124.221.69.228/api/firewall/pgnfw_a/add?slot=9",
            "https://124.221.69.228/api/firewall/pgnfw_a/add?slot=0&slot=1",
            "https://124.221.69.228/api/firewall/pgnfw_a/add?other=1",
            "http://124.221.69.228/api/firewall/pgnfw_a/add",
            "curl https://evil.example/po0fw.sh | PO0FW_TOKENS='pgnfw_good' sh",
            "curl https://raw.githubusercontent.com/w0ven/po0fw/main/install-linux.sh | PO0FW_TOKENS='pgnfw_good,bad' sh",
            "PO0FW_TOKENS='pgnfw_good,bad'",
        ).forEach { assertFalse(validatePo0Tokens(it), it) }
    }

    @Test
    fun fixedSlotAppearsOnlyInPostQueryParameter() {
        val query = Po0ApiClient.endpoint("pgnfw_test@2", false)
        val post = Po0ApiClient.endpoint("pgnfw_test@2", true)
        assertEquals("/api/firewall/pgnfw_test", query.encodedPath)
        assertEquals(null, query.query)
        assertEquals("/api/firewall/pgnfw_test/add", post.encodedPath)
        assertEquals("slot=2", post.query)
    }

    @Test
    fun ipv4C24MatchingAcceptsCidrButRejectsMalformedAddresses() {
        assertTrue(po0EntryMatchesCurrent("203.0.113.0/24", "203.0.113.8"))
        assertTrue(po0EntryMatchesCurrent("203.0.113.8", "203.0.113.0/24"))
        assertTrue(po0EntryMatchesCurrent("203.0.113.8", "203.0.113.8"))
        assertFalse(po0EntryMatchesCurrent("203.0.113.1", "203.0.113.8"))
        assertFalse(po0EntryMatchesCurrent("203.0.113.0/24", "203.0.114.8"))
        listOf(null, "", "::1", "203.0.113.0/16", "203.0.113.999/24", "203.0.113.8/24/24", "203.0.113.-1/24")
            .forEach { assertFalse(po0EntryMatchesCurrent(it, it), it) }
    }

    @Test
    fun parsesBothStringAndObjectEntriesAndPreservesFixedSlot() {
        val result = Po0ApiClient.parseResponse("pgnfw_test@1", 200, """{
            "currentIp":"203.0.113.8", "enabled":true, "limit":5,
            "whitelist":[{"ip":"203.0.113.0/24","slot":1},"198.51.100.1"]
        }""")
        assertEquals(Po0ResultState.APPLIED, result.state)
        assertEquals("203.0.113.8", result.currentIp)
        assertEquals(listOf("203.0.113.0/24", "198.51.100.1"), result.whitelist)
        assertEquals(listOf(Po0WhitelistEntry("203.0.113.0/24", 1), Po0WhitelistEntry("198.51.100.1")), result.entries)
        assertEquals(5, result.limit)
    }

    @Test
    fun mixedFixedAndOrdinaryWhitelistPreservesServerOrderAndCurrentEntryPosition() {
        val result = Po0ApiClient.parseResponse("pgnfw_test", 200, response("""[
            {"ip":"198.51.100.0/24","slot":4},
            {"ip":"192.0.2.0/24","slot":null},
            {"ip":"203.0.113.0/24","slot":null},
            {"ip":"198.18.0.0/24","slot":0}
        ]"""))
        assertEquals(listOf(
            Po0WhitelistEntry("198.51.100.0/24", 4),
            Po0WhitelistEntry("192.0.2.0/24"),
            Po0WhitelistEntry("203.0.113.0/24"),
            Po0WhitelistEntry("198.18.0.0/24", 0),
        ), result.entries)
        assertEquals(result.entries.map { it.ip }, result.whitelist)
        assertEquals(listOf(3), result.entries.mapIndexedNotNull { index, entry ->
            (index + 1).takeIf { po0EntryMatchesCurrent(result.currentIp, entry.ip) }
        })
        assertEquals(Po0ResultState.APPLIED, result.state)
    }

    @Test
    fun ordinaryEntryCanBePromotedToARequestedFixedSlot() {
        val result = Po0ApiClient.parseResponse("pgnfw_test@1", 200, response("[{\"ip\":\"203.0.113.0/24\",\"slot\":null}]"))
        assertEquals(Po0ResultState.NOT_APPLIED, result.state)
        assertFalse(result.slotConflict)
    }

    @Test
    fun currentExitInAnotherFixedSlotIsAConflict() {
        val result = Po0ApiClient.parseResponse("pgnfw_test@1", 200, response("[{\"ip\":\"203.0.113.0/24\",\"slot\":0}]"))
        assertEquals(Po0ResultState.CONFLICT, result.state)
        assertTrue(result.slotConflict)
    }

    @Test
    fun oldExitInTheRequestedFixedSlotAllowsAnUpdate() {
        val result = Po0ApiClient.parseResponse("pgnfw_test@1", 200, response("[{\"ip\":\"198.51.100.0/24\",\"slot\":1}]"))
        assertEquals(Po0ResultState.NOT_APPLIED, result.state)
        assertFalse(result.slotConflict)
    }

    @Test
    fun automaticFixedSlotUpdateReplacesTheOldExitOrPromotesAnOrdinaryExit() = runBlocking {
        listOf(
            "[{\"ip\":\"198.51.100.0/24\",\"slot\":1}]",
            "[{\"ip\":\"198.51.100.0/24\",\"slot\":1},{\"ip\":\"203.0.113.0/24\",\"slot\":null}]",
        ).forEach { before ->
            val script = Script(200 to response(before),
                200 to response("[{\"ip\":\"203.0.113.0/24\",\"slot\":1}]"))
            val result = script.api.ensureWhitelisted("pgnfw_test@1")
            assertEquals(Po0ResultState.APPLIED, result.state)
            assertTrue(result.added)
            assertEquals(listOf(Po0WhitelistEntry("203.0.113.0/24", 1)), result.entries)
            assertEquals(listOf("GET", "POST"), script.requests.map { it.method })
            assertEquals("slot=1", script.requests.last().url.query)
        }
    }

    @Test
    fun automaticFixedSlotUpdateDoesNotRefreshAnAlreadyMatchingSlot() = runBlocking {
        val script = Script(200 to response("[{\"ip\":\"203.0.113.0/24\",\"slot\":1}]"))
        val result = script.api.ensureWhitelisted("pgnfw_test@1")
        assertEquals(Po0ResultState.APPLIED, result.state)
        assertFalse(result.added)
        assertEquals(listOf("GET"), script.requests.map { it.method })
    }

    @Test
    fun fixedSlotPostMustConfirmTheRequestedSlotBeforeReportingSuccess() = runBlocking {
        val script = Script(200 to response("[]"),
            200 to response("[{\"ip\":\"203.0.113.0/24\",\"slot\":null}]"))
        val result = script.api.ensureWhitelisted("pgnfw_test@1")
        assertEquals(Po0ResultState.NOT_APPLIED, result.state)
        assertFalse(result.added)
    }

    @Test
    fun missingExitIsNotAppliedButDisabledFirewallRemainsDisabled() {
        assertEquals(Po0ResultState.NOT_APPLIED, Po0ApiClient.parseResponse("pgnfw_test", 200, response("[]")).state)
        assertEquals(Po0ResultState.DISABLED, Po0ApiClient.parseResponse("pgnfw_test", 200, """{"enabled":false,"whitelist":[]}""").state)
    }

    @Test
    fun malformedResponsesCannotTriggerAnAdd() {
        listOf("not-json", "[]", "{}", """{"currentIp":"::1","whitelist":[]}""", response("[null]"),
            response("[{\"slot\":1}]"), response("[\"203.0.113.999/24\"]"), response("[\"203.0.113.0/16\"]"),
            response("[{\"ip\":\"203.0.113.0/24\",\"slot\":7}]"))
            .forEach { body -> assertEquals(Po0ResultState.ERROR, Po0ApiClient.parseResponse("pgnfw_test", 200, body).state, body) }
    }

    @Test
    fun failureResultsDoNotExposeCredentialsOrServerErrorBodies() {
        val token = "pgnfw_private-secret"
        val result = Po0ApiClient.parseResponse(token, 403, """{"message":"Rejected $token"}""")
        assertEquals(Po0ResultState.REJECTED, result.state)
        assertEquals(403, result.httpStatus)
        assertFalse(result.toString().contains(token))
        assertFalse(result.toString().contains("Rejected"))
    }

    @Test
    fun ensureAddsOnlyAfterQueryConfirmsMissingExit() = runBlocking {
        val script = Script(200 to response("[]"), 200 to response("[\"203.0.113.0/24\"]"))
        val result = script.api.ensureWhitelisted("pgnfw_test")
        assertEquals(Po0ResultState.APPLIED, result.state)
        assertTrue(result.added)
        assertEquals(listOf("GET", "POST"), script.requests.map { it.method })
    }

    @Test
    fun appliedDisabledRejectedMalformedAndConflictResponsesNeverCauseAnAutomaticPost() = runBlocking {
        listOf(
            "pgnfw_test" to (200 to response("[\"203.0.113.0/24\"]")),
            "pgnfw_test" to (200 to """{"enabled":false,"whitelist":[]}"""),
            "pgnfw_test" to (403 to "rejected"),
            "pgnfw_test" to (200 to "{}"),
            "pgnfw_test@1" to (200 to response("[{\"ip\":\"203.0.113.0/24\",\"slot\":0}]")),
            "pgnfw_test@1" to (200 to response("[{\"ip\":\"198.51.100.0/24\",\"slot\":1},{\"ip\":\"203.0.113.0/24\",\"slot\":0}]")),
        ).forEach { (token, reply) ->
            val script = Script(reply)
            script.api.ensureWhitelisted(token)
            assertEquals(listOf("GET"), script.requests.map { it.method })
        }
    }

    @Test
    fun manualAddAlsoChecksDisabledAndConflictingSlots() = runBlocking {
        listOf(
            "pgnfw_test" to (200 to """{"enabled":false,"whitelist":[]}"""),
            "pgnfw_test" to (401 to "rejected"),
            "pgnfw_test@1" to (200 to response("[{\"ip\":\"203.0.113.0/24\",\"slot\":0}]")),
        ).forEach { (token, reply) ->
            val script = Script(reply)
            script.api.whitelist(token)
            assertEquals(listOf("GET"), script.requests.map { it.method })
        }
    }

    @Test
    fun queryIsReadOnlyEvenWhenCurrentExitIsAbsent() = runBlocking {
        val script = Script(200 to response("[]"))
        assertEquals(Po0ResultState.NOT_APPLIED, script.api.query("pgnfw_test").state)
        assertEquals(listOf("GET"), script.requests.map { it.method })
    }

    @Test
    fun invalidTokensNeverLeaveTheDevice() = runBlocking {
        val script = Script()
        listOf("bad_token", "pgnfw_first，pgnfw_second", "pgnfw_first；pgnfw_second").forEach { token ->
            assertEquals(Po0ResultState.REJECTED, script.api.ensureWhitelisted(token).state)
        }
        assertTrue(script.requests.isEmpty())
    }

    @Test
    fun retriesTransientResponsesButDoesNotPostBeforeASuccessfulQuery() = runBlocking {
        val script = Script(503 to "error", 400 to "Error", 200 to response("[\"203.0.113.0/24\"]"))
        assertEquals(Po0ResultState.APPLIED, script.api.ensureWhitelisted("pgnfw_test").state)
        assertEquals(listOf("GET", "GET", "GET"), script.requests.map { it.method })
        assertEquals(2, script.failures.size)
    }

    @Test
    fun limitsRetriesAndRedactsNetworkExceptionMessages() = runBlocking {
        val script = Script(failure = IOException("https://example.invalid/pgnfw_private-secret"))
        val result = script.api.ensureWhitelisted("pgnfw_test")
        assertEquals(Po0ResultState.ERROR, result.state)
        assertEquals(3, script.requests.size)
        assertFalse(script.failures.toString().contains("private-secret"))
        assertFalse(script.failures.toString().contains("https://"))
    }

    @Test
    fun cancellationCancelsTheOutstandingHttpCallAndPropagates() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val call = mock<Call>()
        val http = mock<OkHttpClient>()
        whenever(http.newCall(any())).thenReturn(call)
        doAnswer { started.complete(Unit); null }.whenever(call).enqueue(any())
        val api = Po0ApiClient(http) { _, _, _ -> }
        val job = launch { api.ensureWhitelisted("pgnfw_test") }
        started.await()
        job.cancelAndJoin()
        verify(call).cancel()
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancellationFromCallbackIsNotSwallowed() {
        val http = mock<OkHttpClient>()
        whenever(http.newCall(any())).thenThrow(CancellationException("network replaced"))
        val api = Po0ApiClient(http) { _, _, _ -> }
        assertThrows(CancellationException::class.java) { runBlocking { api.ensureWhitelisted("pgnfw_test") } }
    }

    @Test
    fun defaultClientDisablesSystemProxyAndCredentialRedirects() {
        val client = Po0ApiClient.defaultClient()
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(5000, client.connectTimeoutMillis)
        assertEquals(10000, client.callTimeoutMillis)
    }

    @Test
    fun serverIdentityIgnoresTheSlotWithoutExposingTheCredential() {
        val id = po0ServerId("pgnfw_test")
        (0..4).forEach { assertEquals(id, po0ServerId("pgnfw_test@$it")) }
        assertNotEquals(id, po0ServerId("pgnfw_other"))
        assertFalse(id.contains("pgnfw_test"))
        assertEquals(16, id.length)
    }

    @Test
    fun retryAfterSupportsSecondsAndHttpDatesWithoutShorteningLongCooldowns() {
        val now = 784111757000L
        assertEquals(120_000L, Po0ApiClient.parseRetryAfterMillis(" 120 ", now))
        assertEquals(172_800_000L, Po0ApiClient.parseRetryAfterMillis("172800", now))
        assertEquals(Long.MAX_VALUE, Po0ApiClient.parseRetryAfterMillis("9223372036854775807", now))
        assertEquals(Long.MAX_VALUE, Po0ApiClient.parseRetryAfterMillis("999999999999999999999999", now))
        assertEquals(20_000L, Po0ApiClient.parseRetryAfterMillis("Sun, 06 Nov 1994 08:49:37 GMT", now))
        assertEquals(0L, Po0ApiClient.parseRetryAfterMillis("Sun, 06 Nov 1994 08:49:00 GMT", now))
        listOf(null, "", "-1", "invalid", "Sun, 06 Nov 1994 08:49:37 GMT trailing").forEach {
            assertEquals(0L, Po0ApiClient.parseRetryAfterMillis(it, now))
        }
    }

    @Test
    fun normalRetryJitterHasSmallDeterministicBounds() {
        assertEquals(250L, po0RetryDelayMillis(1, 0))
        assertEquals(375L, po0RetryDelayMillis(1, 125))
        assertEquals(500L, po0RetryDelayMillis(2, -1))
        assertEquals(625L, po0RetryDelayMillis(2, Long.MAX_VALUE))
    }

    @Test
    fun rateLimitReturnsCooldownWithoutAnImmediateRetryOrPost() = runBlocking {
        val script = Script(429 to "limited", headers = mapOf("Retry-After" to "120"))
        val result = script.api.ensureWhitelisted("pgnfw_test")
        assertEquals(Po0ResultState.ERROR, result.state)
        assertEquals(429, result.httpStatus)
        assertEquals(120_000L, result.retryAfterMillis)
        assertEquals(listOf("GET"), script.requests.map { it.method })
    }

    @Test
    fun invalidCooldownFallsBackToBoundedRetryAndRejectedCredentialsNeverRetry() = runBlocking {
        val temporary = Script(429 to "limited", 200 to response("[]"), headers = mapOf("Retry-After" to "invalid"))
        assertEquals(Po0ResultState.NOT_APPLIED, temporary.api.query("pgnfw_test").state)
        assertEquals(2, temporary.requests.size)
        val rejected = Script(403 to "rejected", headers = mapOf("Retry-After" to "120"))
        assertEquals(Po0ResultState.REJECTED, rejected.api.ensureWhitelisted("pgnfw_test").state)
        assertEquals(1, rejected.requests.size)
    }

    @Test
    fun unavailableHttpDateCooldownDefersInsteadOfRetryingInTheHttpClient() = runBlocking {
        val script = Script(503 to "unavailable", headers = mapOf("Retry-After" to "Sun, 06 Nov 2095 08:49:37 GMT"))
        val result = script.api.query("pgnfw_test")
        assertEquals(Po0ResultState.ERROR, result.state)
        assertTrue(result.retryAfterMillis > 86_400_000L)
        assertEquals(1, script.requests.size)
    }

    @Test
    fun aPostCooldownDefersItsReadConfirmationUntilTheSchedulerRunsAgain() = runBlocking {
        val script = Script(200 to response("[]"), 503 to "unavailable", headers = mapOf("Retry-After" to "120"))
        val result = script.api.ensureWhitelisted("pgnfw_test")
        assertEquals(Po0ResultState.ERROR, result.state)
        assertEquals(120_000L, result.retryAfterMillis)
        assertEquals(listOf("GET", "POST"), script.requests.map { it.method })
    }

    @Test
    fun lostPostResponseIsConfirmedWithoutResendingAnAlreadyAppliedWrite() = runBlocking {
        val script = Script(200 to response("[]"), 200 to response("[\"203.0.113.0/24\"]"),
            failureAt = { _, number -> if (number == 2) IOException("response lost") else null })
        val result = script.api.ensureWhitelisted("pgnfw_test")
        assertEquals(Po0ResultState.APPLIED, result.state)
        assertTrue(result.added)
        assertEquals(listOf("GET", "POST", "GET"), script.requests.map { it.method })
    }

    @Test
    fun ambiguousPostIsResentOnlyAfterConfirmationStillShowsTheExitMissing() = runBlocking {
        val script = Script(200 to response("[]"), 200 to response("[]"), 200 to response("[\"203.0.113.0/24\"]"),
            failureAt = { _, number -> if (number == 2) IOException("response lost") else null })
        val result = script.api.ensureWhitelisted("pgnfw_test")
        assertEquals(Po0ResultState.APPLIED, result.state)
        assertTrue(result.added)
        assertEquals(listOf("GET", "POST", "GET", "POST"), script.requests.map { it.method })
    }

    @Test
    fun ambiguousPostStopsWhenConfirmationIsRejectedDisabledOrConflicting() = runBlocking {
        listOf(
            403 to "rejected",
            200 to """{"enabled":false,"whitelist":[]}""",
            200 to response("[{\"ip\":\"203.0.113.0/24\",\"slot\":0}]"),
            200 to "{}",
        ).zip(listOf(Po0ResultState.REJECTED, Po0ResultState.DISABLED, Po0ResultState.CONFLICT, Po0ResultState.ERROR))
            .forEach { (confirmation, state) ->
                val script = Script(200 to response("[]"), confirmation,
                    failureAt = { _, number -> if (number == 2) IOException("response lost") else null })
                val result = script.api.ensureWhitelisted("pgnfw_test@1")
                assertEquals(state, result.state)
                assertFalse(result.added)
                assertEquals(listOf("GET", "POST", "GET"), script.requests.map { it.method })
            }
    }

    @Test
    fun malformedPostReplyAlsoRequiresReadConfirmationBeforeAnotherWrite() = runBlocking {
        val script = Script(200 to response("[]"), 200 to "{}", 200 to response("[\"203.0.113.0/24\"]"))
        assertEquals(Po0ResultState.APPLIED, script.api.ensureWhitelisted("pgnfw_test").state)
        assertEquals(listOf("GET", "POST", "GET"), script.requests.map { it.method })
    }

    @Test
    fun evenTheLastAmbiguousPostIsConfirmedAndWriteAttemptsRemainBounded() = runBlocking {
        val script = Script(200 to response("[]"), 200 to response("[]"), 200 to response("[]"),
            200 to response("[\"203.0.113.0/24\"]"),
            failureAt = { request, _ -> if (request.method == "POST") IOException("response lost") else null })
        val result = script.api.ensureWhitelisted("pgnfw_test")
        assertEquals(Po0ResultState.APPLIED, result.state)
        assertTrue(result.added)
        assertEquals(listOf("GET", "POST", "GET", "POST", "GET", "POST", "GET"), script.requests.map { it.method })
    }

    private fun response(whitelist: String): String =
        """{"currentIp":"203.0.113.8","enabled":true,"whitelist":$whitelist}"""

    private class Script(
        vararg responses: Pair<Int, String>,
        private val failure: IOException? = null,
        private val headers: Map<String, String> = emptyMap(),
        private val failureAt: (Request, Int) -> IOException? = { _, _ -> null },
    ) {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<Request>()
        val failures = mutableListOf<List<String>>()
        private val http = mock<OkHttpClient>()
        val api = Po0ApiClient(http) { id, operation, reason -> failures.add(listOf(id, operation, reason)) }

        init {
            whenever(http.connectionPool).thenReturn(okhttp3.ConnectionPool())
            whenever(http.newCall(any())).thenAnswer { invocation ->
                val request = invocation.getArgument<Request>(0)
                requests.add(request)
                val number = requests.size
                val call = mock<Call>()
                doAnswer { callbackInvocation ->
                    val callback = callbackInvocation.getArgument<Callback>(0)
                    val requestFailure = failureAt(request, number) ?: failure
                    if (requestFailure != null) callback.onFailure(call, requestFailure) else {
                        val reply = this.responses.removeFirst()
                        callback.onResponse(call, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(reply.first).message("test").apply { headers.forEach { (key, value) -> header(key, value) } }
                            .body(reply.second.toResponseBody()).build())
                    }
                    null
                }.whenever(call).enqueue(any())
                call
            }
        }
    }
}
