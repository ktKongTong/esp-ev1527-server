package com.esp32.control.contract

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire-shape tests for the contract. These strings mirror the JSON documented
 * in docs/api-contract-v1.md.
 */
class ContractSerializationTest {

    @Test
    fun deviceWireShapeDecodes() {
        val wire = """
            {
              "id": "dev_523F47A849D20ACC2AA890CD",
              "name": "1 号增氧机",
              "location": "1 号塘",
              "codeOn": 1714739,
              "codeOff": 1714740,
              "rf": { "protocol": 1, "length": 24, "repeat": 5 },
              "createdAtEpochMs": 1730000000000,
              "futureField": "ignored"
            }
        """.trimIndent()

        val device = ContractJson.decodeFromString<Device>(wire)

        assertEquals(1714739L, device.codeOn)
        assertEquals(1, device.rf.protocol)
        assertEquals(24, device.rf.length)
        assertEquals(5, device.rf.repeat)
    }

    @Test
    fun deviceWireShapeCarriesNoState() {
        val device = Device(
            id = "dev_1",
            name = "1 号增氧机",
            location = "1 号塘",
            codeOn = 1714739,
            codeOff = 1714740,
            rf = RfParameters(protocol = 1, length = 24, repeat = 5),
            createdAtEpochMs = 1730000000000,
        )

        val encoded = ContractJson.encodeToString(device)

        assertFalse(encoded.contains("\"state\""), encoded)
        assertFalse(encoded.contains("\"online\""), encoded)
        assertFalse(encoded.contains("\"revision\""), encoded)
    }

    @Test
    fun updateDeviceRequestKeepsOmittedFieldsNull() {
        val request = ContractJson.decodeFromString<UpdateDeviceRequest>(
            """{"name":"2 号增氧机"}""",
        )

        assertEquals("2 号增氧机", request.name)
        assertEquals(null, request.location)

        val encoded = ContractJson.encodeToString(UpdateDeviceRequest(name = "2 号增氧机"))
        assertTrue(encoded.contains("\"name\":\"2 号增氧机\""), encoded)
        assertTrue(encoded.contains("\"location\":null"), encoded)
    }

    @Test
    fun commandRequestAcceptsSnakeCaseActions() {
        val on = ContractJson.decodeFromString<CommandRequest>("""{"action":"turn_on"}""")
        val off = ContractJson.decodeFromString<CommandRequest>("""{"action":"turn_off"}""")

        assertEquals(CommandAction.TURN_ON, on.action)
        assertEquals(CommandAction.TURN_OFF, off.action)
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
    fun groupWireShapeDecodesMembers() {
        val wire = """
            {
              "id": "grp_1",
              "name": "1 号塘",
              "deviceIds": ["dev_1", "dev_2"],
              "createdAtEpochMs": 1730000000000,
              "updatedAtEpochMs": 1730000000001
            }
        """.trimIndent()

        val group = ContractJson.decodeFromString<Group>(wire)

        assertEquals("1 号塘", group.name)
        assertEquals(listOf("dev_1", "dev_2"), group.deviceIds)

        val request = ContractJson.encodeToString(
            CreateGroupRequest(name = "1 号塘", deviceIds = listOf("dev_1")),
        )
        assertTrue(request.contains("\"deviceIds\":[\"dev_1\"]"), request)
    }

    @Test
    fun scheduleWireShapeDecodesRecurrence() {
        val wire = """
            {
              "id": "sch_1",
              "name": "morning",
              "targetType": "group",
              "targetId": "grp_1",
              "action": "turn_on",
              "rrule": "FREQ=DAILY",
              "timeZone": "Asia/Shanghai",
              "startsAtEpochMs": 1730000000000,
              "enabled": true,
              "nextRunAtEpochMs": 1730003600000,
              "lastRunAtEpochMs": null,
              "lastResult": "dispatched",
              "lastError": null,
              "createdAtEpochMs": 1730000000000,
              "updatedAtEpochMs": 1730000000001
            }
        """.trimIndent()

        val schedule = ContractJson.decodeFromString<Schedule>(wire)

        assertEquals(ScheduleTarget.GROUP, schedule.targetType)
        assertEquals(CommandAction.TURN_ON, schedule.action)
        assertEquals(ScheduleResult.DISPATCHED, schedule.lastResult)
        assertEquals("FREQ=DAILY", schedule.rrule)
        assertEquals(null, schedule.lastRunAtEpochMs)
    }

    @Test
    fun updateScheduleRequestKeepsOmittedFieldsNull() {
        val request = ContractJson.decodeFromString<UpdateScheduleRequest>(
            """{"enabled":false}""",
        )

        assertEquals(false, request.enabled)
        assertEquals(null, request.rrule)
        assertEquals(null, request.startsAtEpochMs)

        val encoded = ContractJson.encodeToString(UpdateScheduleRequest(enabled = false))
        assertTrue(encoded.contains("\"enabled\":false"), encoded)
        assertTrue(encoded.contains("\"rrule\":null"), encoded)
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
