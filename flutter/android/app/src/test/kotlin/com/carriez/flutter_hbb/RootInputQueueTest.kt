package com.carriez.flutter_hbb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RootInputQueueTest {
    @Test
    fun moveBurstKeepsReleaseAndLatestPosition() {
        val queue = RootInputQueue(3)
        assertTrue(queue.offer("D 10 20"))
        repeat(1000) { assertTrue(queue.offer("M $it $it")) }
        assertTrue(queue.offer("U 999 999"))

        assertEquals("D 10 20", queue.poll(0, TimeUnit.MILLISECONDS))
        assertEquals("M 999 999", queue.poll(0, TimeUnit.MILLISECONDS))
        assertEquals("U 999 999", queue.poll(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun fullQueueDiscardsOnlyMoveAndPreservesGestureBoundaries() {
        val queue = RootInputQueue(4)
        listOf("D 1 2", "M 3 4", "U 3 4", "D 5 6").forEach {
            assertTrue(queue.offer(it))
        }
        assertTrue(queue.offer("U 5 6"))

        assertEquals(listOf("D 1 2", "U 3 4", "D 5 6", "U 5 6"),
            List(4) { queue.poll(0, TimeUnit.MILLISECONDS) })
    }

    @Test
    fun controlOnlyOverflowReportsFailureInsteadOfLosingRelease() {
        val queue = RootInputQueue(2)
        assertTrue(queue.offer("D 1 2"))
        assertTrue(queue.offer("U 1 2"))
        assertFalse(queue.offer("K 4"))

        assertEquals("D 1 2", queue.poll(0, TimeUnit.MILLISECONDS))
        assertEquals("U 1 2", queue.poll(0, TimeUnit.MILLISECONDS))
        queue.cancel()
        assertEquals("C", queue.poll(0, TimeUnit.MILLISECONDS))
        assertEquals(null, queue.poll(0, TimeUnit.MILLISECONDS))
    }
}
