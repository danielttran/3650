package com.Bible3650.www.data.text

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads UTF-8 text without allowing an untrusted document provider to grow memory unboundedly. */
internal fun InputStream.readUtf8UpTo(maxBytes: Int): String? {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray().decodeToString()
}
