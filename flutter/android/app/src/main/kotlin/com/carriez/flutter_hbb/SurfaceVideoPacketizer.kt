package com.carriez.flutter_hbb

internal class SurfaceVideoPacketizer {
    private var config = byteArrayOf()

    fun setConfig(bytes: ByteArray) {
        require(bytes.size <= 65536 && isAnnexB(bytes)) { "Unsupported codec configuration" }
        config = bytes.copyOf()
    }

    fun frame(bytes: ByteArray, key: Boolean): ByteArray {
        require(bytes.size <= MAX_BYTES && isAnnexB(bytes)) { "Unsupported encoded packet" }
        if (!key) return bytes
        require(config.isNotEmpty() && config.size + bytes.size <= MAX_BYTES) { "Missing codec configuration" }
        return config + bytes
    }

    private fun isAnnexB(bytes: ByteArray): Boolean = bytes.size > 4 &&
        bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
        (bytes[2] == 1.toByte() || bytes[2] == 0.toByte() && bytes[3] == 1.toByte())

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
    }
}
