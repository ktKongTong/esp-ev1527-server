package com.esp32.control.server.domain

/**
 * Trims a user-supplied name or location and returns null when the result is
 * not 1-64 characters. Learning confirmation and device editing share this
 * rule so the two paths cannot drift.
 */
internal fun normalizeDeviceLabel(value: String): String? {
    val trimmed = value.trim()
    return trimmed.takeIf { it.isNotEmpty() && it.length <= 64 }
}
