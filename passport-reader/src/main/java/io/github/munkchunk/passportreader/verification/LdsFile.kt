package io.github.munkchunk.passportreader.verification

/**
 * The elementary files of the LDS as the chip stores them: one BER-TLV each,
 * with a single-byte tag.
 */
internal object LdsFile {

    const val EF_SOD = 0x77
    const val EF_DG14 = 0x6E
    const val EF_DG15 = 0x6F

    private const val BYTE_MASK = 0xFF
    private const val SHORT_LENGTH_LIMIT = 0x80
    private const val MAX_LENGTH_BYTES = 3

    /**
     * The value inside [bytes], which must be a TLV tagged [tag]. Definite
     * lengths only, with up to three length bytes.
     */
    fun value(bytes: ByteArray, tag: Int): ByteArray {
        require(bytes.size >= 2 && (bytes[0].toInt() and BYTE_MASK) == tag) {
            "expected tag %02X".format(tag)
        }
        var offset = 1
        val first = bytes[offset++].toInt() and BYTE_MASK
        val length = if (first < SHORT_LENGTH_LIMIT) {
            first
        } else {
            val count = first - SHORT_LENGTH_LIMIT
            require(count in 1..MAX_LENGTH_BYTES && offset + count <= bytes.size) { "unsupported length" }
            var value = 0
            repeat(count) { value = (value shl Byte.SIZE_BITS) or (bytes[offset++].toInt() and BYTE_MASK) }
            value
        }
        require(offset + length <= bytes.size) { "value runs past the end of the file" }
        return bytes.copyOfRange(offset, offset + length)
    }
}
