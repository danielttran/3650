package com.Bible3650.www.data.text

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal HTTP GET abstraction so the downloader can be unit-tested with a fake. */
interface HttpFetcher {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String
}

@Singleton
class UrlHttpFetcher @Inject constructor() : HttpFetcher {
    override suspend fun get(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Bible3650")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            if (conn.contentLengthLong > MAX_HTTP_RESPONSE_BYTES) {
                throw IOException("HTTP response too large for $url")
            }
            conn.inputStream.use { stream ->
                stream.readUtf8UpTo(MAX_HTTP_RESPONSE_BYTES)
                    ?: throw IOException("HTTP response too large for $url")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MAX_HTTP_RESPONSE_BYTES = 64 * 1024 * 1024
    }
}
