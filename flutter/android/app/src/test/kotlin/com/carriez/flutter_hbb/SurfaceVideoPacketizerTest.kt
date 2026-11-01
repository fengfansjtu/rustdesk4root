package com.carriez.flutter_hbb

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SurfaceVideoPacketizerTest {
    private val config = byteArrayOf(0, 0, 0, 1, 0x67, 1)
    private val frame = byteArrayOf(0, 0, 0, 1, 0x65, 2)

    @Test
    fun keyframesCarryConfigurationAfterRestartOrCongestion() {
        val packets = SurfaceVideoPacketizer()
        packets.setConfig(config)
        assertArrayEquals(config + frame, packets.frame(frame, true))
        assertArrayEquals(frame, packets.frame(frame, false))
        assertArrayEquals(config + frame, packets.frame(frame, true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun keyframeWithoutConfigurationFailsCleanly() {
        SurfaceVideoPacketizer().frame(frame, true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedPacketLayoutFailsCleanly() {
        val packets = SurfaceVideoPacketizer()
        packets.setConfig(config)
        packets.frame(byteArrayOf(0, 0, 0, 2, 0x65, 2), true)
    }
}
