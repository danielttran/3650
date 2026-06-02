package com.Bible3650.www.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRequestTrackerTest {
    @Test
    fun `new requests supersede older playlist builds`() {
        val tracker = PlaylistRequestTracker()
        val first = tracker.next()
        val second = tracker.next()

        assertFalse(tracker.isCurrent(first))
        assertTrue(tracker.isCurrent(second))
    }

    @Test
    fun `invalidation cancels the current playlist build`() {
        val tracker = PlaylistRequestTracker()
        val request = tracker.next()

        tracker.invalidate()

        assertFalse(tracker.isCurrent(request))
    }
}
