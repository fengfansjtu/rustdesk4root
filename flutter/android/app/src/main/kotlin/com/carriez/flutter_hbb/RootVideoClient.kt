package com.carriez.flutter_hbb

import android.util.Log
import ffi.FFI
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegedVideoUnavailableException(message: String) : IllegalStateException(message)

internal class RootVideoClient private constructor(
    val session: Long,
    private val socket: Socket,
    private val writer: BufferedWriter,
    private val input: DataInputStream,
) {
    private val running = AtomicBoolean(true)
    private val writeLock = Any()
    private val worker = Thread(::drain, "RootVideoClient-$session").apply { isDaemon = true }

    fun updateBitrate(bitrate: Int) {
        if (!running.get() || bitrate <= 0) return
        synchronized(writeLock) {
            if (!running.get()) return
            runCatching {
                writer.write("B $bitrate\n")
                writer.flush()
            }
        }
    }

    private fun requestKeyframe() {
        if (!running.get()) return
        synchronized(writeLock) {
            if (!running.get()) return
            runCatching {
                writer.write("K\n")
                writer.flush()
            }
        }
    }

    fun close() {
        running.set(false)
        runCatching { socket.close() }
        if (Thread.currentThread() != worker) {
            runCatching { worker.join(250) }
        }
    }

    private fun drain() {
        var failed = false
        try {
            while (running.get()) {
                val size = input.readInt()
                if (size == 0) continue
                check(size in 1..(8 * 1024 * 1024)) { "Invalid privileged video packet size: $size" }
                val key = input.readByte().toInt() != 0
                val ptsUs = input.readLong()
                val payload = ByteArray(size)
                input.readFully(payload)
                if (FFI.onSurfaceVideoPacket(session, payload, ptsUs, key)) {
                    requestKeyframe()
                }
            }
        } catch (error: Exception) {
            failed = running.get()
            if (failed) {
                Log.w(TAG, "Privileged video encoder stopped", error)
            }
        } finally {
            running.set(false)
            runCatching { socket.close() }
            FFI.onSurfaceVideoStopped(session, failed)
        }
    }

    companion object {
        private const val TAG = "RootVideoClient"

        fun start(
            port: Int,
            token: String,
            session: Long,
            codec: Int,
            width: Int,
            height: Int,
            dpi: Int,
            bitrate: Int,
            fps: Int,
            hardware: Boolean,
        ): RootVideoClient {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1500)
                socket.tcpNoDelay = true
                socket.soTimeout = 2500
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                writer.write("$token\n")
                writer.write("V $session $codec $width $height $dpi $bitrate $fps ${if (hardware) 1 else 0}\n")
                writer.flush()
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 65536))
                val status = input.readInt()
                if (status == -1) {
                    throw PrivilegedVideoUnavailableException("Privileged VirtualDisplay rejected by system ($status)")
                }
                if (status != 0) {
                    throw IllegalStateException("Privileged Surface encoder start failed ($status)")
                }
                socket.soTimeout = 0
                return RootVideoClient(session, socket, writer, input).apply {
                    worker.start()
                }
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw error
            }
        }
    }
}
