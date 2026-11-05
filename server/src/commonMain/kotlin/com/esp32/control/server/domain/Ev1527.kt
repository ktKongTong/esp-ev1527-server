package com.esp32.control.server.domain

/**
 * EV1527 frames are fixed at 24 bits: a 20-bit address plus a 4-bit key.
 *
 * The gateway is configured for EV1527 only. Frames that do not match this
 * shape are dropped at ingest instead of being normalized, because a weak
 * frame decoded under the wrong timing template still looks like a valid
 * 24-bit code (for example `392004 == 196002 * 2` under an inverted protocol).
 */
object Ev1527 {
    const val PROTOCOL = 1
    const val LENGTH = 24
    const val ADDRESS_BITS = 20
    const val KEY_BITS = 4
    const val MAX_VALUE = (1L shl LENGTH) - 1

    private const val KEY_MASK = (1L shl KEY_BITS) - 1

    /** Both gate conditions are required; either one alone accepts mis-decoded frames. */
    fun matches(protocol: Int, length: Int): Boolean =
        protocol == PROTOCOL && length == LENGTH

    fun isValid(value: Long): Boolean = value in 0..MAX_VALUE

    fun addressOf(value: Long): Long = value ushr KEY_BITS

    fun keyOf(value: Long): Int = (value and KEY_MASK).toInt()

    /** ON and OFF keys of one remote share the same 20-bit address. */
    fun sameAddress(a: Long, b: Long): Boolean = addressOf(a) == addressOf(b)
}
