package com.lechugones.cocina.provider

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.test.assertEquals

class ProviderCallsTest {
    private fun httpError(
        code: Int,
        retryAfter: String? = null,
    ): HttpException {
        val raw =
            okhttp3.Response
                .Builder()
                .code(code)
                .message("x")
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .request(
                    okhttp3.Request
                        .Builder()
                        .url("https://example.test/")
                        .build(),
                ).apply { retryAfter?.let { header("Retry-After", it) } }
                .build()
        return HttpException(Response.error<Any>("".toResponseBody(), raw))
    }

    @Test
    fun `requests are spaced by the minimum interval, even when concurrent`() =
        runTest {
            val starts = mutableListOf<Long>()
            val limiter = RateLimiter(1_200, nowMillis = { testScheduler.currentTime }, sleep = { delay(it) })
            (1..3)
                .map { async { limiter.run { starts += testScheduler.currentTime } } }
                .awaitAll()
            assertEquals(listOf(0L, 1_200L, 2_400L), starts)
        }

    @Test
    fun `timeouts and 5xx are retried twice, then reported`() =
        runTest {
            var calls = 0
            val limiter = RateLimiter(0)
            val result =
                providerCall<Unit>(limiter, sleep = {}) {
                    calls++
                    throw IOException("down")
                }
            assertEquals(ProviderResult.Failure(ProviderError.Offline), result)
            assertEquals(3, calls)

            calls = 0
            val recovered = providerCall(limiter, sleep = {}) { if (++calls < 2) throw httpError(503) else "ok" }
            assertEquals(ProviderResult.Success("ok"), recovered)
        }

    @Test
    fun `403 is blocked and never retried`() =
        runTest {
            var calls = 0
            val result =
                providerCall<Unit>(RateLimiter(0), sleep = {}) {
                    calls++
                    throw httpError(403)
                }
            assertEquals(ProviderResult.Failure(ProviderError.Blocked), result)
            assertEquals(1, calls)
        }

    @Test
    fun `429 reports Retry-After, other 4xx are plain errors`() =
        runTest {
            assertEquals(
                ProviderResult.Failure(ProviderError.RateLimited(30)),
                providerCall<Unit>(RateLimiter(0), sleep = {}) { throw httpError(429, "30") },
            )
            assertEquals(
                ProviderResult.Failure(ProviderError.Http(404)),
                providerCall<Unit>(RateLimiter(0), sleep = {}) { throw httpError(404) },
            )
        }
}
