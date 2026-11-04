package com.esp32.control.server.routes

import com.esp32.control.contract.ConfirmLearningRequest
import com.esp32.control.contract.StartLearningRequest
import com.esp32.control.contract.TestLearningRequest
import com.esp32.control.server.ApiException
import com.esp32.control.server.domain.LearningService
import com.esp32.control.server.learningNotFound
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.learningRoute(learning: LearningService) {
    post("/learning/sessions") {
        val length = call.request.contentLength()
        val request = if (length == null || length == 0L) {
            StartLearningRequest()
        } else {
            call.receive<StartLearningRequest>()
        }
        call.respond(HttpStatusCode.Created, learning.start(request))
    }

    get("/learning/sessions/{sessionId}") {
        val sessionId = call.parameters["sessionId"]
            ?: throw learningNotFound()
        call.respond(learning.getLearning(sessionId))
    }

    post("/learning/sessions/{sessionId}/candidates/{candidateId}/accept") {
        val sessionId = call.parameters["sessionId"] ?: throw learningNotFound()
        val candidateId = call.parameters["candidateId"]
            ?: throw ApiException(
                HttpStatusCode.NotFound,
                "candidate_not_found",
                "Candidate not found",
            )
        call.respond(learning.acceptCandidate(sessionId, candidateId))
    }

    post("/learning/sessions/{sessionId}/rescan") {
        val sessionId = call.parameters["sessionId"] ?: throw learningNotFound()
        call.respond(learning.rescan(sessionId))
    }

    post("/learning/sessions/{sessionId}/test") {
        val sessionId = call.parameters["sessionId"] ?: throw learningNotFound()
        val request = call.receive<TestLearningRequest>()
        call.respond(
            HttpStatusCode.Accepted,
            learning.testLearning(sessionId, request.slot),
        )
    }

    post("/learning/sessions/{sessionId}/confirm") {
        val sessionId = call.parameters["sessionId"] ?: throw learningNotFound()
        val request = call.receive<ConfirmLearningRequest>()
        val confirmation = learning.confirmLearning(sessionId, request)
        call.response.header(HttpHeaders.Location, "/api/v1/devices/${confirmation.device.id}")
        call.respond(HttpStatusCode.Created, confirmation)
    }

    delete("/learning/sessions/{sessionId}") {
        val sessionId = call.parameters["sessionId"] ?: throw learningNotFound()
        learning.cancelLearning(sessionId)
        call.respond(HttpStatusCode.NoContent)
    }
}
