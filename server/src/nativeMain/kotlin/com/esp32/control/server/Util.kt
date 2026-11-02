package com.esp32.control.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval
import kotlin.random.Random

@OptIn(ExperimentalForeignApi::class)
fun nowEpochMs(): Long = memScoped {
    val tv = alloc<timeval>()
    if (gettimeofday(tv.ptr, null) != 0) {
        0L
    } else {
        tv.tv_sec * 1000L + tv.tv_usec / 1000L
    }
}

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
