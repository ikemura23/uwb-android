package com.ikemura23.uwb.oob

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

class ControleeInfo(val address: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is ControleeInfo && address.contentEquals(other.address)

    override fun hashCode(): Int = address.contentHashCode()
}

class ControllerInfo(
    val address: ByteArray,
    val channel: Int,
    val preambleIndex: Int,
    val sessionId: Int,
    val sessionKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ControllerInfo &&
            address.contentEquals(other.address) &&
            channel == other.channel &&
            preambleIndex == other.preambleIndex &&
            sessionId == other.sessionId &&
            sessionKey.contentEquals(other.sessionKey)

    override fun hashCode(): Int {
        var result = address.contentHashCode()
        result = 31 * result + channel
        result = 31 * result + preambleIndex
        result = 31 * result + sessionId
        result = 31 * result + sessionKey.contentHashCode()
        return result
    }
}

/** Big-endian OOB wire format. Malformed input throws [IllegalArgumentException]. */
object OobCodec {
    private const val TYPE_CONTROLLER: Byte = 0x01
    private const val TYPE_CONTROLEE: Byte = 0x02

    fun encode(info: ControleeInfo): ByteArray {
        requireFitsU8(info.address.size, "address length")
        return ByteBuffer.allocate(2 + info.address.size)
            .put(TYPE_CONTROLEE)
            .put(info.address.size.toByte())
            .put(info.address)
            .array()
    }

    fun encode(info: ControllerInfo): ByteArray {
        requireFitsU8(info.address.size, "address length")
        requireFitsU8(info.channel, "channel")
        requireFitsU8(info.preambleIndex, "preambleIndex")
        requireFitsU8(info.sessionKey.size, "key length")
        return ByteBuffer.allocate(2 + info.address.size + 2 + 4 + 1 + info.sessionKey.size)
            .put(TYPE_CONTROLLER)
            .put(info.address.size.toByte())
            .put(info.address)
            .put(info.channel.toByte())
            .put(info.preambleIndex.toByte())
            .putInt(info.sessionId)
            .put(info.sessionKey.size.toByte())
            .put(info.sessionKey)
            .array()
    }

    fun decodeControlee(bytes: ByteArray): ControleeInfo = decode(bytes) { buf ->
        requireType(buf, TYPE_CONTROLEE)
        ControleeInfo(buf.readBytes())
    }

    fun decodeController(bytes: ByteArray): ControllerInfo = decode(bytes) { buf ->
        requireType(buf, TYPE_CONTROLLER)
        val address = buf.readBytes()
        val channel = buf.get().toInt() and 0xff
        val preambleIndex = buf.get().toInt() and 0xff
        val sessionId = buf.getInt()
        ControllerInfo(address, channel, preambleIndex, sessionId, buf.readBytes())
    }

    private fun <T> decode(bytes: ByteArray, block: (ByteBuffer) -> T): T =
        try {
            block(ByteBuffer.wrap(bytes))
        } catch (e: BufferUnderflowException) {
            throw IllegalArgumentException("Truncated OOB message", e)
        }

    private fun requireType(buf: ByteBuffer, expected: Byte) {
        val type = buf.get()
        require(type == expected) { "Unexpected OOB message type: $type" }
    }

    /** Reads a u8 length prefix followed by that many bytes. */
    private fun ByteBuffer.readBytes(): ByteArray {
        val len = get().toInt() and 0xff
        return ByteArray(len).also { get(it) }
    }

    private fun requireFitsU8(value: Int, name: String) =
        require(value in 0..255) { "$name out of range: $value" }
}
