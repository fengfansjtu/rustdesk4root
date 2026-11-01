package com.carriez.flutter_hbb

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import ffi.FFI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class SurfaceVideoEncoder private constructor(
    val session: Long,
    private val codec: MediaCodec,
    val inputSurface: Surface,
    bitrate: Int,
    private val bitrateRange: android.util.Range<Int>
) {
    private val running = AtomicBoolean(true)
    private val requestedBitrate = AtomicInteger(bitrate)
    private val output = Thread({ drain(bitrate) }, "SurfaceVideoOutput")

    fun start() = output.start()

    fun updateBitrate(bitrate: Int) {
        requestedBitrate.set(bitrateRange.clamp(bitrate))
    }

    fun close() {
        running.set(false)
        if (Thread.currentThread() != output) output.join(250)
    }

    private fun drain(initialBitrate: Int) {
        var failed = false
        var appliedBitrate = initialBitrate
        val packets = SurfaceVideoPacketizer()
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val bitrate = requestedBitrate.get()
                if (appliedBitrate != bitrate) {
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
                    appliedBitrate = bitrate
                }
                when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        var config = byteArrayOf()
                        for (key in arrayOf("csd-0", "csd-1", "csd-2")) {
                            val buffer = format.getByteBuffer(key)?.duplicate() ?: continue
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            config += bytes
                        }
                        if (config.isNotEmpty()) packets.setConfig(config)
                    }
                    else -> if (index >= 0) {
                        var sync = false
                        try {
                            check(info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME == 0) { "Partial video packet" }
                            check(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "Video encoder ended" }
                            if (info.size > 0) {
                                check(info.size <= 8 * 1024 * 1024) { "Video packet too large" }
                                val buffer = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    packets.setConfig(bytes)
                                } else {
                                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                    sync = FFI.onSurfaceVideoPacket(session, packets.frame(bytes, key), info.presentationTimeUs, key)
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                        if (sync) codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    }
                }
            }
        } catch (error: Exception) {
            failed = running.get()
            if (failed) Log.w(TAG, "Surface encoder stopped", error)
        } finally {
            running.set(false)
            try { codec.stop() } catch (error: Exception) { Log.w(TAG, "Encoder stop failed", error) }
            try { codec.release() } catch (error: Exception) { Log.w(TAG, "Encoder release failed", error) }
            try { inputSurface.release() } catch (error: Exception) { Log.w(TAG, "Encoder surface release failed", error) }
            FFI.onSurfaceVideoStopped(session, failed)
        }
    }

    companion object {
        private const val TAG = "SurfaceVideoEncoder"

        fun capabilities(width: Int, height: Int): BooleanArray {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return BooleanArray(4)
            return booleanArrayOf(
                candidates(0, width, height, true).isNotEmpty(),
                candidates(1, width, height, true).isNotEmpty(),
                candidates(0, width, height, false).isNotEmpty(),
                candidates(1, width, height, false).isNotEmpty()
            )
        }

        private fun mime(codec: Int) = if (codec == 0) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC

        private fun candidates(codec: Int, width: Int, height: Int, hardware: Boolean): List<MediaCodecInfo> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
            return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                try {
                    info.isEncoder && (if (hardware) info.isHardwareAccelerated else info.isSoftwareOnly) &&
                        info.supportedTypes.any { it.equals(mime(codec), true) } &&
                        info.getCapabilitiesForType(mime(codec)).let { caps ->
                            caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                                caps.videoCapabilities?.isSizeSupported(width, height) == true
                        }
                } catch (_: IllegalArgumentException) { false }
            }
        }

        fun create(session: Long, format: Int, width: Int, height: Int, bitrate: Int, fps: Int, hardware: Boolean): SurfaceVideoEncoder {
            var lastError: Exception = IllegalStateException("No compatible Surface encoder")
            for (info in candidates(format, width, height, hardware)) {
                var codec: MediaCodec? = null
                var surface: Surface? = null
                try {
                    val caps = checkNotNull(info.getCapabilitiesForType(mime(format)).videoCapabilities)
                    val targetBitrate = caps.bitrateRange.clamp(bitrate)
                    val targetFps = fps.coerceIn(1, 60).toDouble().coerceAtMost(caps.getSupportedFrameRatesFor(width, height).upper)
                    val mediaFormat = MediaFormat.createVideoFormat(mime(format), width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, targetFps.toInt().coerceAtLeast(1))
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                        setInteger(MediaFormat.KEY_LATENCY, 1)
                        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100000L)
                        setFloat("max-fps-to-encoder", targetFps.toFloat())
                    }
                    codec = MediaCodec.createByCodecName(info.name)
                    codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surface = codec.createInputSurface()
                    codec.start()
                    Log.i(TAG, "Using ${info.name}, ${width}x$height, fps=$targetFps, hardware=$hardware")
                    return SurfaceVideoEncoder(session, codec, surface, targetBitrate, caps.bitrateRange)
                } catch (error: Exception) {
                    lastError = error
                    surface?.release()
                    try { codec?.release() } catch (releaseError: Exception) { Log.w(TAG, "Encoder release failed", releaseError) }
                }
            }
            throw lastError
        }
    }
}
