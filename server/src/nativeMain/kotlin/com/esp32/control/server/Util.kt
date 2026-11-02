package com.esp32.control.server

import kotlin.random.Random
import kotlin.time.Clock

fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

fun newId(prefix: String): String {
    val bytes = Random.nextBytes(12)
    val hex = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }
    return "${prefix}_$hex"
}

private const val HEX = "0123456789ABCDEF"
