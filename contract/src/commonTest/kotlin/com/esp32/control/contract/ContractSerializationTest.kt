package com.esp32.control.contract

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Wire-shape tests for contract v1. These strings mirror the JSON documented
 * in docs/contract-v1.md and the payloads the server emits over HTTP and SSE.
 */
class ContractSerializationTest {

    @Test
    fun deviceWireShapeDecodes() {
        val wire = """
            {
              "id": "dev_523F47A849D20ACC2AA890CD",
              "name": "1 号增氧机",
              "location": "1 号塘",
              "state": "unknown",
              "stateEvidence": "unknown",
              "online": true,
              "codeOn": 1714739,
              "codeOff": 1714740,
              "rf": { "protocol": 1, "length": 24, "repeat": 5 },
              "lastAction": null,
              "revision": 1,
              "createdAtEpochMs": 1730000000000,
              "futureField": "ignored"
            }
        """.trimIndent()

        val device = ContractJson.decodeFromString<Device>(wire)

        assertEquals(DeviceState.UNKNOWN, device.state)
        assertEquals(StateEvidence.UNKNOWN, device.stateEvidence)
        assertEquals(1714739L, device.codeOn)
        assertEquals(1, device.rf.protocol)
        assertEquals(24, device.rf.length)
        assertEquals(5, device.rf.repeat)
    }

    @Test
    fun commandRequestAcceptsSnakeCaseActions() {
        val on = ContractJson.decodeFromString<CommandRequest>("""{"action":"turn_on"}""")
        val off = ContractJson.decodeFromString<CommandRequest>("""{"action":"turn_off"}""")

        assertEquals(CommandAction.TURN_ON, on.action)
        assertEquals(CommandAction.TURN_OFF, off.action)
    }

    @Test
    fun eventsUseTypeDiscriminatorOnEncodeAndDecode() {
        val wire = """
            {
              "type": "device.removed",
              "eventId": "evt_1",
              "atEpochMs": 1730000000123,
              "deviceId": "dev_1",
              "removedAtEpochMs": 1730000000123,
              "revision": 4
            }
        """.trimIndent()

        val event = ContractJson.decodeFromString<ServerEvent>(wire)

        assertIs<DeviceRemovedEvent>(event)
        assertEquals("device.removed", event.type)
        assertEquals("dev_1", event.deviceId)

        val encoded = ContractJson.encodeToString(ServerEvent.serializer(), event)
        assertTrue(encoded.contains("\"type\":\"device.removed\""), encoded)
    }

    @Test
    fun learningSessionEncodesContractStateNames() {
        val session = LearningSession(
            id = "lsn_1",
            state = LearningState.CANDIDATE_ON_READY,
            revision = 2,
            createdAtEpochMs = 1730000000000,
            sessionExpiresAtEpochMs = 1730000006000,
            collectionExpiresAtEpochMs = 1730000003000,
            candidateExpiresAtEpochMs = 1730000003030,
            requiredHits = 3,
            candidates = emptyList(),
        )

        val encoded = ContractJson.encodeToString(session)

        assertTrue(encoded.contains("\"state\":\"candidate_on_ready\""), encoded)
        assertTrue(encoded.contains("\"requiredHits\":3"), encoded)
    }

    @Test
    fun errorEnvelopeRoundTrips() {
        val wire = """
            {
              "error": {
                "code": "code_already_registered",
                "message": "RF code is already registered",
                "requestId": "req_1",
                "details": { "conflictDeviceId": "dev_1" }
              }
            }
        """.trimIndent()

        val envelope = ContractJson.decodeFromString<ApiErrorEnvelope>(wire)

        assertEquals("code_already_registered", envelope.error.code)
        assertEquals("dev_1", envelope.error.details?.get("conflictDeviceId"))

        val encoded = ContractJson.encodeToString(envelope)
        assertTrue(encoded.contains("\"requestId\":\"req_1\""), encoded)
    }
}
