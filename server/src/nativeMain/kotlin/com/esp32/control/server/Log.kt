package com.esp32.control.server

import kotlin.time.Clock

/**
 * Leveled stdout logging.
 *
 * The native binary runs under Docker, where stdout is the only log sink, and
 * the project has no logging dependency. Call sites pass a lambda so message
 * arguments are only built for enabled levels. Set `LOG_LEVEL` to
 * `debug`, `info`, `warn` or `error`.
 *
 * Reused: Kotlin standard library `println` and `kotlin.time.Clock`.
 * Not reused because: no logging framework exists in the dependency set, and
 * a single-file logger is cheaper than adding one (AGENTS.md §3).
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    private var threshold = Level.DEBUG

    fun configure(level: Level) {
        threshold = level
    }

    fun debug(message: () -> String) = emit(Level.DEBUG, message)

    fun info(message: () -> String) = emit(Level.INFO, message)

    fun warn(message: () -> String) = emit(Level.WARN, message)

    fun error(message: () -> String) = emit(Level.ERROR, message)

    fun error(cause: Throwable, message: () -> String) {
        if (!enabled(Level.ERROR)) return
        println("${timestamp()} ERROR ${message()}: ${cause.message}")
        cause.printStackTrace()
    }

    private fun emit(level: Level, message: () -> String) {
        if (!enabled(level)) return
        println("${timestamp()} ${level.name} ${message()}")
    }

    private fun enabled(level: Level): Boolean = level.ordinal >= threshold.ordinal

    private fun timestamp(): String = Clock.System.now().toString()
}

/**
 * Short, single-line payload preview for MQTT diagnostics. Binary payloads are
 * decoded leniently; neither credentials nor arbitrary request bodies pass
 * through here.
 */
internal fun ByteArray.logPreview(limit: Int = 240): String {
    val text = decodeToString().trim()
    return if (text.length <= limit) {
        text
    } else {
        "${text.take(limit)}... (${size}B)"
    }
}
