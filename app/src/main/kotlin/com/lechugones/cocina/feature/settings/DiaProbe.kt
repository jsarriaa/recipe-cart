package com.lechugones.cocina.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject

/** What one probe request got back. [code] is null when the request did not complete. */
data class ProbeResult(
    val step: String,
    val code: Int?,
    val contentType: String?,
    /** True when the body looks like JSON (an API answer), false for an HTML block page. */
    val json: Boolean,
)

/**
 * The Día feasibility check of docs/providers/dia.md (phase 7), debug builds only: exactly two
 * requests, on the phone's own connection, with the app's honest User-Agent (the shared client).
 * No browser headers and no retries: a 403 or an HTML page means Día stays manual-only.
 */
class DiaProbe
    @Inject
    constructor(
        client: OkHttpClient,
    ) {
        /** The postal-code step sets a session cookie that the search needs; kept only in memory. */
        private val client =
            client
                .newBuilder()
                .cookieJar(
                    object : CookieJar {
                        private val cookies = mutableListOf<Cookie>()

                        override fun saveFromResponse(
                            url: HttpUrl,
                            cookies: List<Cookie>,
                        ) {
                            this.cookies += cookies
                        }

                        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
                    },
                ).build()

        suspend fun run(postalCode: String): List<ProbeResult> =
            withContext(Dispatchers.IO) {
                val first =
                    call(
                        "save-shipping-address",
                        Request
                            .Builder()
                            .url("$BASE/common-aggregator/save-shipping-address?new_postal_code=$postalCode")
                            .put(ByteArray(0).toRequestBody())
                            .build(),
                    )
                if (first.code == null || first.code == FORBIDDEN || !first.json && first.code != NO_CONTENT) return@withContext listOf(first)
                val second =
                    call(
                        "search",
                        Request
                            .Builder()
                            .url("$BASE/search-back/search/reduced?q=leche&page=1&page_size=1")
                            .get()
                            .build(),
                    )
                listOf(first, second)
            }

        private fun call(
            step: String,
            request: Request,
        ): ProbeResult =
            try {
                client.newCall(request).execute().use { response ->
                    val type = response.header("Content-Type")
                    val start =
                        response
                            .peekBody(PEEK_BYTES)
                            .string()
                            .trimStart()
                    ProbeResult(step, response.code, type, json = start.startsWith("{") || start.startsWith("["))
                }
            } catch (e: IOException) {
                ProbeResult(step, null, null, json = false)
            }

        private companion object {
            const val BASE = "https://www.dia.es/api/v1"
            const val FORBIDDEN = 403
            const val NO_CONTENT = 204
            const val PEEK_BYTES = 64L
        }
    }
