package com.v2ray.ang.util

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0TokenResult
import com.v2ray.ang.dto.Po0WhitelistEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val po0TokenSeparators = Regex("[,，|;；、\\s]+")
private val po0TokenPattern = Regex("^pgnfw_[^\\s,，|;；、@/\\\\?#\"'`$(){}<>]+(?:@[0-4])?$")

fun po0TokenId(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

/** Split without filtering: invalid input must remain visible to form validation. */
fun parsePo0Tokens(text: String): List<String> = po0Inputs(text).distinct()

fun validatePo0Tokens(text: String): Boolean = parsePo0Tokens(text).all(::isValidPo0Token)

fun validatePo0IntervalSeconds(value: Int): Boolean = value in 1..3600

/** Extract only known credential forms; this function never starts a shell or downloads a script. */
fun extractPo0Tokens(text: String): List<String> = parsePo0Tokens(text)

/**
 * Matches the server's current exit against one entry without changing whitelist order.
 * Modern Po0 responses use /24 CIDRs; two plain IPv4 values retain legacy exact-IP semantics,
 * matching w0ven/po0fw scripts/po0-firewall-whitelist.js and po0-clash's sameC24 contract.
 */
fun po0EntryMatchesCurrent(currentIp: String?, entryIp: String?): Boolean =
    Po0ApiClient.sameC24(currentIp, entryIp)

private fun isValidPo0Token(token: String): Boolean = token.length <= 1024 && po0TokenPattern.matches(token)

private fun po0Inputs(text: String): List<String> {
    val input = text.trim()
    if (input.isEmpty()) return emptyList()
    // A complete copy of the official configuration file may include blank/comment lines.
    val lines = input.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
    val assignmentPattern = Regex("(?:export\\s+)?PO0FW_TOKENS\\s*=\\s*([\"'])(.*?)\\1")
    val assignments = lines.map { assignmentPattern.matchEntire(it) }
    if (assignments.all { it != null }) return assignments.flatMap { splitPo0Text(it!!.groupValues[2]) }
    // Complete official installation snippets are parsed as text, never evaluated as shell code.
    if (Regex("(?:^|\\s)(?:curl|wget|irm|bash|powershell)(?:\\s|$)").containsMatchIn(input)) {
        val urls = Regex("https?://[^\\s\"'<>)]*").findAll(input).map { it.value }.toList()
        if (urls.isEmpty()) return listOf(input)
        val apiTokens = urls.map(::tokenFromPo0Url)
        if (apiTokens.zip(urls).all { (tokens, url) -> tokens != listOf(url) }) return apiTokens.flatten()
        if (!urls.all(::isOfficialPo0Script)) return listOf(input)
        val values = assignmentPattern.findAll(input).map { it.groupValues[2] }.toList() +
            Regex("-Tokens\\s+([\"'])(.*?)\\1", RegexOption.IGNORE_CASE).findAll(input).map { it.groupValues[2] }.toList()
        if (values.isNotEmpty()) return values.flatMap(::splitPo0Text)
        val arguments = Regex("pgnfw_[^\\s,，|;；、\"'<>)]*").findAll(input).map { it.value }.toList()
        if (arguments.isNotEmpty()) return arguments
        return listOf(input)
    }
    if (assignments.any { it != null }) return listOf(input)
    return splitPo0Text(input)
}

private fun splitPo0Text(text: String): List<String> = text.split(po0TokenSeparators)
    .filter(String::isNotEmpty).flatMap(::tokenFromPo0Url)

private fun isOfficialPo0Script(url: String): Boolean {
    val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
    if (parsed.scheme != "https" || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
    if (parsed.query != null || parsed.fragment != null) return false
    return (parsed.host == "raw.githubusercontent.com" && parsed.encodedPath in listOf(
        "/w0ven/po0fw/main/po0fw.sh",
        "/w0ven/po0fw/main/install-linux.sh",
        "/w0ven/po0fw/main/openwrt/install-openwrt.sh",
        "/w0ven/po0fw/main/windows/install-windows.ps1",
    )) || (parsed.host == "po0fw.uuuz.de" && parsed.encodedPath in listOf("/", "/po0fw.sh", "/install.sh"))
}

private fun tokenFromPo0Url(value: String): List<String> {
    val url = runCatching { value.toHttpUrl() }.getOrNull() ?: return listOf(value)
    if (url.scheme != "https" || url.host != Po0ApiClient.API_HOST || url.port != 443 ||
        url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return listOf(value)
    val parts = url.pathSegments
    if (parts.size !in 3..4 || parts[0] != "api" || parts[1] != "firewall" ||
        (parts.size == 4 && parts[3] != "add")) return listOf(value)
    val slot = url.queryParameter("slot")
    if (url.queryParameterNames.any { it != "slot" } || url.queryParameterValues("slot").size > 1 ||
        (slot != null && (parts.size != 4 || slot !in listOf("0", "1", "2", "3", "4")))) return listOf(value)
    val token = parts[2] + if (slot != null) "@$slot" else ""
    return if (isValidPo0Token(token)) listOf(token) else listOf(value)
}

/**
 * Dedicated transport for Po0 credentials. Scheduling and physical-network selection belong to
 * the service; this client owns the protocol and cancellable requests only.
 * Protocol reference: yuuuki-creation/po0-clash v6.2.0 lib/common/po0_firewall.dart (GPL-3.0),
 * https://github.com/w0ven/po0fw/blob/main/po0fw.sh and the official Po0 Wiki.
 */
class Po0ApiClient internal constructor(
    private val client: OkHttpClient,
    private val reportFailure: (String, String, String) -> Unit,
) : Closeable {
    constructor(client: OkHttpClient = defaultClient()) : this(client, { id, operation, reason ->
        LogUtil.w(AppConfig.TAG, "Po0 API $operation direct tokenId=$id", IOException("Po0 $operation failed ($reason)"))
    })

    suspend fun query(token: String): Po0TokenResult = call(token, add = false)

    suspend fun whitelist(token: String): Po0TokenResult {
        val result = query(token)
        if (result.state !in setOf(Po0ResultState.APPLIED, Po0ResultState.NOT_APPLIED)) return result
        return call(token, add = true)
    }

    /** Read first: unnecessarily adding a new exit can evict another device's FIFO record. */
    suspend fun ensureWhitelisted(token: String): Po0TokenResult {
        val result = query(token)
        if (result.state != Po0ResultState.NOT_APPLIED || result.slotConflict) return result
        return call(token, add = true)
    }

    fun resetConnections() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    override fun close() = resetConnections()

    private suspend fun call(token: String, add: Boolean): Po0TokenResult = withContext(Dispatchers.IO) {
        val base = Po0TokenResult(po0TokenId(token), Po0ResultState.ERROR, requestedSlot = slotOf(token))
        if (!isValidPo0Token(token)) return@withContext base.copy(state = Po0ResultState.REJECTED)
        var lastStatus: Int? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MILLIS * attempt)
            try {
                val response = send(token, add)
                lastStatus = response.status
                if (!isTransient(response)) {
                    return@withContext parse(token, response).let { it.copy(added = add && it.state == Po0ResultState.APPLIED) }
                }
                client.connectionPool.evictAll()
                logFailure(token, if (add) "add" else "query", "HTTP ${response.status}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                client.connectionPool.evictAll()
                // Exception messages and causes may contain the complete credential-bearing URL.
                logFailure(token, if (add) "add" else "query", error.javaClass.simpleName)
            }
        }
        base.copy(httpStatus = lastStatus)
    }

    private suspend fun send(token: String, add: Boolean): HttpResponse = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(endpoint(token, add))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .method(if (add) "POST" else "GET", if (add) ByteArray(0).toRequestBody() else null)
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Po0 request failed (${e.javaClass.simpleName})"))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val source = it.body.source()
                        if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("Po0 response exceeds size limit")
                        HttpResponse(it.code, source.readUtf8())
                    }
                    continuation.resume(result)
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Po0 response failed (${error.javaClass.simpleName})"))
                }
            }
        })
    }

    private fun logFailure(token: String, operation: String, reason: String) {
        reportFailure(po0TokenId(token), operation, reason)
    }

    private data class HttpResponse(val status: Int, val body: String)

    companion object {
        const val API_HOST = "124.221.69.228"
        private val API_BASE = "https://$API_HOST/api/firewall".toHttpUrl()
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MILLIS = 250L
        private const val MAX_RESPONSE_BYTES = 65536L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        internal fun endpoint(token: String, add: Boolean): HttpUrl = API_BASE.newBuilder()
            .addPathSegment(token.substringBefore('@'))
            .apply {
                if (add) {
                    addPathSegment("add")
                    slotOf(token)?.let { addQueryParameter("slot", it.toString()) }
                }
            }.build()

        private fun slotOf(token: String): Int? = if ('@' in token) token.substringAfterLast('@').toIntOrNull() else null

        private fun isTransient(response: HttpResponse): Boolean =
            response.status == 429 || response.status >= 500 ||
                (response.status == 400 && decodeObject(response.body) == null)

        private fun decodeObject(body: String) = try {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: RuntimeException) {
            null
        }

        internal fun sameC24(first: String?, second: String?): Boolean {
            val a = ipv4(first) ?: return false
            val b = ipv4(second) ?: return false
            return if (a.second || b.second) a.first ushr 8 == b.first ushr 8 else a.first == b.first
        }

        private fun ipv4(value: String?): Pair<Long, Boolean>? {
            if (value.isNullOrEmpty()) return null
            val hasPrefix = value.endsWith("/24")
            val parts = (if (hasPrefix) value.dropLast(3) else value).split('.')
            if (parts.size != 4) return null
            var result = 0L
            for (part in parts) {
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                result = result shl 8 or octet.toLong()
            }
            return result to hasPrefix
        }

        private fun parse(token: String, response: HttpResponse): Po0TokenResult = parseResponse(token, response.status, response.body)

        internal fun parseResponse(token: String, status: Int, body: String): Po0TokenResult {
            val base = Po0TokenResult(po0TokenId(token), Po0ResultState.ERROR, httpStatus = status, requestedSlot = slotOf(token))
            if (status !in 200..299) return base.copy(state = if (status in 300..499 && status != 429) Po0ResultState.REJECTED else Po0ResultState.ERROR)
            val data = decodeObject(body) ?: return base
            val currentIp = string(data["currentIp"]).takeIf { ipv4(it) != null }
            val whitelist = data["whitelist"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return base
            val entries = mutableListOf<Po0WhitelistEntry>()
            for (entry in whitelist) {
                val ip = string(if (entry.isJsonObject) entry.asJsonObject["ip"] else entry)
                if (ipv4(ip) == null) return base
                val slotElement = if (entry.isJsonObject) entry.asJsonObject["slot"] else null
                val slot = if (slotElement == null || slotElement.isJsonNull) null else {
                    if (!slotElement.isJsonPrimitive || !slotElement.asJsonPrimitive.isNumber) return base
                    slotElement.asString.toIntOrNull()?.takeIf { it in 0..4 } ?: return base
                }
                entries.add(Po0WhitelistEntry(ip!!, slot))
            }
            val disabled = data["enabled"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean } == true
            if (!disabled && currentIp == null) return base
            val matching = entries.filter { sameC24(it.ip, currentIp) }
            // Official po0fw script: /add?slot=N replaces that slot's old exit and promotes an
            // ordinary entry. Only moving an exit from another fixed slot needs panel deletion.
            val slotConflict = base.requestedSlot != null &&
                matching.any { it.slot != null && it.slot != base.requestedSlot }
            val applied = if (base.requestedSlot == null) matching.isNotEmpty()
                else matching.any { it.slot == base.requestedSlot }
            val limit = data["limit"]?.let {
                if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asString.toIntOrNull()?.takeIf { count -> count >= 0 } else null
            }
            return base.copy(
                state = when {
                    disabled -> Po0ResultState.DISABLED
                    slotConflict -> Po0ResultState.CONFLICT
                    applied -> Po0ResultState.APPLIED
                    else -> Po0ResultState.NOT_APPLIED
                },
                currentIp = currentIp,
                whitelist = entries.map { it.ip },
                entries = entries,
                limit = limit,
                slotConflict = slotConflict,
            )
        }

        private fun string(value: JsonElement?): String? = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }
}
