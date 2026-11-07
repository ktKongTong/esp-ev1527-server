package com.esp32.control.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ev1527Test {
    // Captured from a real EV1527 remote through OpenMQTTGateway.
    private val keyOn = 196002L
    private val keyOff = 196004L

    @Test
    fun acceptsOnly24BitFrames() {
        assertTrue(Ev1527.acceptsLength(Ev1527.LENGTH))
        assertFalse(Ev1527.acceptsLength(21))
        assertFalse(Ev1527.acceptsLength(25))
    }

    @Test
    fun splits20BitAddressAnd4BitKey() {
        assertEquals(0x02FDAL, Ev1527.addressOf(keyOn))
        assertEquals(2, Ev1527.keyOf(keyOn))
        assertEquals(0x02FDAL, Ev1527.addressOf(keyOff))
        assertEquals(4, Ev1527.keyOf(keyOff))
        assertTrue(Ev1527.sameAddress(keyOn, keyOff))
    }

    @Test
    fun rejectsKeysFromAnotherRemote() {
        assertFalse(Ev1527.sameAddress(keyOn, 1714739L))
    }

    @Test
    fun decoderArtifactsAreNumericallyValid() {
        // Field-log artifacts: the truncated frames fail the length gate.
        // `392004` (196002 * 2, reported protocol 6) is still a 24-bit frame,
        // and protocol is not a gate.
        assertTrue(Ev1527.isValid(24500)) // 196002 >> 3, reported length 21
        assertTrue(Ev1527.isValid(382)) // 196002 >> 9, reported length 15
        assertTrue(Ev1527.isValid(5)) // 196002 >> 15, reported length 9
        assertTrue(Ev1527.isValid(392004)) // 196002 * 2, reported protocol 6
    }

    @Test
    fun acceptsOnlyValuesFitting24Bits() {
        assertTrue(Ev1527.isValid(0))
        assertTrue(Ev1527.isValid(Ev1527.MAX_VALUE))
        assertFalse(Ev1527.isValid(-1))
        assertFalse(Ev1527.isValid(1L shl Ev1527.LENGTH))
    }
}
