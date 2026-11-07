package com.esp32.control.server.domain

/**
 * EV1527 frames are fixed at 24 bits: a 20-bit address plus a 4-bit key.
 *
 * The gateway reports a protocol number from whichever decoder template
 * matched the frame. That number is metadata, not part of the code, so it is
 * preserved but never used for gating. Only the frame length identifies the
 * EV1527 shape.
 */
object Ev1527 {
    const val LENGTH = 24
    const val ADDRESS_BITS = 20
    const val KEY_BITS = 4
    const val MAX_VALUE = (1L shl LENGTH) - 1

    private const val KEY_MASK = (1L shl KEY_BITS) - 1

    fun acceptsLength(length: Int): Boolean = length == LENGTH

    fun isValid(value: Long): Boolean = value in 0..MAX_VALUE

    fun addressOf(value: Long): Long = value ushr KEY_BITS

    fun keyOf(value: Long): Int = (value and KEY_MASK).toInt()

    /** ON and OFF keys of one remote share the same 20-bit address. */
    fun sameAddress(a: Long, b: Long): Boolean = addressOf(a) == addressOf(b)
}
