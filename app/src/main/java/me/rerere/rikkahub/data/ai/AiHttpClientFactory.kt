package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.AiHttpClientProvider
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.atomic.AtomicLong

/** Builds AI-only clients for the selected network experiment mode. */
class AiHttpClientFactory internal constructor(
    private val baseClient: OkHttpClient,
    private val modeProvider: () -> AiNetworkMode,
    private val logConfiguration: Boolean,
) : AiHttpClientProvider {
    constructor(baseClient: OkHttpClient, settingsStore: SettingsStore) : this(
        baseClient = baseClient,
        modeProvider = { settingsStore.settingsFlow.value.aiNetworkMode },
        logConfiguration = true,
    )

    private val clientSequence = AtomicLong(0)
    private val poolSequence = AtomicLong(0)
    private val sharedPoolId = "shared-pool-${System.identityHashCode(baseClient.connectionPool)}"

    private val defaultClient by lazy {
        buildClient(
            mode = AiNetworkMode.DEFAULT,
            pool = baseClient.connectionPool,
            poolId = sharedPoolId,
            protocols = null,
        )
    }

    private val http1Client by lazy {
        buildClient(
            mode = AiNetworkMode.HTTP1_ONLY,
            pool = baseClient.connectionPool,
            poolId = sharedPoolId,
            protocols = listOf(Protocol.HTTP_1_1),
        )
    }

    override fun clientForRequest(): OkHttpClient = when (modeProvider()) {
        AiNetworkMode.DEFAULT -> defaultClient
        AiNetworkMode.HTTP1_ONLY -> http1Client
        AiNetworkMode.FRESH_CONNECTION -> {
            val pool = ConnectionPool()
            buildClient(
                mode = AiNetworkMode.FRESH_CONNECTION,
                pool = pool,
                poolId = "fresh-pool-${poolSequence.incrementAndGet()}-${System.identityHashCode(pool)}",
                protocols = null,
            )
        }
    }

    private fun buildClient(
        mode: AiNetworkMode,
        pool: ConnectionPool,
        poolId: String,
        protocols: List<Protocol>?,
    ): OkHttpClient {
        val builder = baseClient.newBuilder().connectionPool(pool)
        protocols?.let { builder.protocols(it) }
        val configuredClient = builder.build()
        return configuredClient.newDiagnosedAiClient(
            identity = AiHttpClientDiagnosticIdentity(
                networkMode = mode.name,
                clientId = "ai-client-${clientSequence.incrementAndGet()}",
                connectionPoolId = poolId,
            ),
            logConfiguration = logConfiguration,
        )
    }
}
