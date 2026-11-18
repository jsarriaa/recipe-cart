package com.lechugones.cocina.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Spaces requests to one provider at least [minIntervalMillis] apart, one at a time, whoever
 * calls (docs/providers/README.md § Etiquette, rule 2).
 */
class RateLimiter(
    private val minIntervalMillis: Long,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var lastStart: Long? = null

    suspend fun <T> run(block: suspend () -> T): T =
        mutex.withLock {
            lastStart?.let { last ->
                val wait = last + minIntervalMillis - nowMillis()
                if (wait > 0) sleep(wait)
            }
            lastStart = nowMillis()
            block()
        }

    companion object {
        /** Default: 1 request per 1.2 s. */
        const val DEFAULT_INTERVAL_MILLIS = 1_200L
    }
}

/**
 * Runs one provider request through [limiter], mapping failures to [ProviderError]. Timeouts
 * and 5xx are retried at most twice ([backoffMillis]); 403 and other 4xx never are; 429 is
 * reported with its Retry-After.
 */
suspend fun <T> providerCall(
    limiter: RateLimiter,
    backoffMillis: List<Long> = listOf(2_000L, 6_000L),
    sleep: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> T,
): ProviderResult<T> {
    var attempt = 0
    while (true) {
        val error =
            try {
                return ProviderResult.Success(limiter.run(block))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                when (val code = e.code()) {
                    403 -> {
                        ProviderError.Blocked
                    }

                    429 -> {
                        ProviderError.RateLimited(
                            e
                                .response()
                                ?.headers()
                                ?.get("Retry-After")
                                ?.toLongOrNull(),
                        )
                    }

                    else -> {
                        ProviderError.Http(code)
                    }
                }
            } catch (e: SerializationException) {
                ProviderError.Parse(e.message.orEmpty())
            } catch (e: IllegalArgumentException) {
                ProviderError.Parse(e.message.orEmpty())
            } catch (e: InterruptedIOException) {
                ProviderError.Offline // Timeout: retryable.
            } catch (e: IOException) {
                ProviderError.Offline
            }
        val retryable = error == ProviderError.Offline || (error is ProviderError.Http && error.code >= 500)
        if (!retryable || attempt >= backoffMillis.size) return ProviderResult.Failure(error)
        sleep(backoffMillis[attempt++])
    }
}
