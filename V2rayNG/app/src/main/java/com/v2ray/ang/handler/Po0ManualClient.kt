package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.dto.Po0TokenResult
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Po0ApiClient
import com.v2ray.ang.util.po0ServerId
import com.v2ray.ang.util.po0TokenId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/** Uses only the selected physical Network, independent of proxy profiles and system proxy settings. */
internal fun po0NetworkClient(network: Network): Po0ApiClient = Po0ApiClient(
    Po0ApiClient.defaultClient().newBuilder()
        .socketFactory(network.socketFactory)
        .dns { hostname -> network.getAllByName(hostname).toList() }
        .build()
)

internal fun po0PhysicalNetworkRequest(): NetworkRequest = NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    .build()

internal fun isPo0NetworkReady(capabilities: NetworkCapabilities): Boolean =
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

/** One manual batch owns one physical-network generation; a handover requires an explicit retry. */
internal class Po0ManualNetworkSession<T : Any> {
    private var available: T? = null
    private var selected: T? = null
    private var closed = false
    private val ready = CompletableDeferred<T>()
    private val invalidated = CompletableDeferred<Unit>()

    @Synchronized
    fun onAvailable(network: T) {
        if (closed) return
        if (selected != null && selected != network) invalidated.complete(Unit)
        available = network
    }

    @Synchronized
    fun onCapabilitiesChanged(network: T, isReady: Boolean) {
        if (closed || available != network || invalidated.isCompleted) return
        if (selected == network) {
            if (!isReady) invalidated.complete(Unit)
        } else if (isReady) {
            selected = network
            ready.complete(network)
        }
    }

    @Synchronized
    fun onLost(network: T) {
        if (closed) return
        if (available == network) available = null
        if (selected == network) invalidated.complete(Unit)
    }

    @Synchronized
    fun close() { closed = true }

    suspend fun <R : Any> run(block: suspend (T) -> R): R? {
        val network = withTimeoutOrNull(8_000L) { ready.await() } ?: return null
        return coroutineScope {
            // Install cancellation before dispatch, including a handover between selection and HTTP setup.
            val request = async(start = CoroutineStart.LAZY) { block(network) }
            val cancellation = invalidated.invokeOnCompletion {
                request.cancel(CancellationException("Po0 manual physical network changed"))
            }
            try {
                val result = request.await()
                if (invalidated.isCompleted) throw IOException("Po0 physical network changed; retry the manual request")
                result
            } catch (error: CancellationException) {
                // A network invalidates only this HTTP child; lifecycle cancellation must still propagate.
                coroutineContext.ensureActive()
                if (invalidated.isCompleted) throw IOException("Po0 physical network changed; retry the manual request")
                throw error
            } finally {
                cancellation.dispose()
            }
        }
    }
}

object Po0ManualClient {
    suspend fun run(context: Context, settings: Po0Settings, queryOnly: Boolean): Po0RunStatus = withContext(Dispatchers.IO) {
        val normalized = normalizePo0Settings(settings.copy(enabled = false))
        val tokens = normalized.activeTokens
        require(tokens.isNotEmpty()) { "Po0 credentials required" }
        Po0ServiceControl.runInDaemon(context, tokens.map(::po0TokenId), queryOnly)?.let { return@withContext it }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) return@withContext Po0RunStatus(
            checkedAt = System.currentTimeMillis(), totalCount = tokens.size, error = true, revision = UUID.randomUUID().toString()
        )

        val session = Po0ManualNetworkSession<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = session.onAvailable(network)

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                session.onCapabilitiesChanged(network, isPo0NetworkReady(capabilities))

            override fun onLost(network: Network) = session.onLost(network)
        }
        var registered = false
        try {
            connectivity.requestNetwork(po0PhysicalNetworkRequest(), callback)
            registered = true
            session.run { network ->
                val client = po0NetworkClient(network)
                try {
                    po0RunStatus(po0RunTokens(client, tokens, queryOnly, coordinator = Po0RequestCoordinator(context)))
                } finally {
                    client.close()
                }
            } ?: Po0RunStatus(checkedAt = System.currentTimeMillis(), totalCount = tokens.size, error = true, revision = UUID.randomUUID().toString())
        } finally {
            session.close()
            if (registered) {
                try {
                    connectivity.unregisterNetworkCallback(callback)
                } catch (error: Exception) {
                    LogUtil.w(AppConfig.TAG, "Po0 manual direct network cleanup failed", IOException(error.javaClass.simpleName))
                }
            }
        }
    }
}

internal suspend fun po0RunTokens(
    client: Po0ApiClient,
    tokens: List<String>,
    queryOnly: Boolean = false,
    coordinator: Po0RequestCoordinator? = null,
    reportFailure: (String, Exception) -> Unit = { tokenId, error ->
        // Credential-bearing exception messages/causes must not enter logs.
        LogUtil.w(AppConfig.TAG, "Po0 batch direct tokenId=$tokenId failed", IOException(error.javaClass.simpleName))
    },
): List<Po0TokenResult> =
    coroutineScope {
        val permits = Semaphore(3)
        val conflicts = tokens.groupingBy(::po0ServerId).eachCount().filterValues { it > 1 }.keys
        tokens.map { token ->
            async {
                if (po0ServerId(token) in conflicts) return@async Po0TokenResult(
                    po0TokenId(token), Po0ResultState.CONFLICT,
                    requestedSlot = token.substringAfterLast('@', "").toIntOrNull(), localConflict = true,
                )
                permits.withPermit {
                    try {
                        val request: suspend () -> Po0TokenResult = {
                            if (queryOnly) client.query(token) else client.ensureWhitelisted(token)
                        }
                        coordinator?.run(token, request) ?: request()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        reportFailure(po0TokenId(token), error)
                        Po0TokenResult(po0TokenId(token), Po0ResultState.ERROR,
                            requestedSlot = token.substringAfterLast('@', "").toIntOrNull())
                    }
                }
            }
        }.awaitAll()
    }

internal fun po0RunStatus(results: List<Po0TokenResult>, now: Long = System.currentTimeMillis()): Po0RunStatus = Po0RunStatus(
    checkedAt = now,
    successCount = results.count { it.state == Po0ResultState.APPLIED },
    totalCount = results.size,
    running = false,
    error = results.any { it.state != Po0ResultState.APPLIED },
    results = results,
    revision = UUID.randomUUID().toString(),
)
