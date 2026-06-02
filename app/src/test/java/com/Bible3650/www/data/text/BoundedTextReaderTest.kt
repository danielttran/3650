package com.Bible3650.www.data.text

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedTextReaderTest {
    @Test
    fun `reads utf8 text up to the byte limit`() {
        val text = "Grace and peace"

        assertEquals(text, ByteArrayInputStream(text.toByteArray()).readUtf8UpTo(text.toByteArray().size))
    }

    @Test
    fun `rejects a stream once it exceeds the byte limit`() {
        assertNull(ByteArrayInputStream("12345".toByteArray()).readUtf8UpTo(4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects negative limits`() {
        ByteArrayInputStream(byteArrayOf()).readUtf8UpTo(-1)
    }
}
