package com.lechugones.cocina.provider

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Session cookies for one provider, in memory only (never written to disk). */
class MemoryCookieJar : CookieJar {
    private val cookies = mutableMapOf<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        cookies.forEach { this.cookies[it.name + "@" + it.domain + it.path] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        this.cookies.values.removeAll { it.expiresAt < now }
        return this.cookies.values.filter { it.matches(url) }
    }
}
