package com.ticketsales.queue.adapter

import com.ticketsales.queue.EnterQueueUseCase
import com.ticketsales.queue.LeaveQueueUseCase
import com.ticketsales.queue.QueueRejectReason
import com.ticketsales.queue.QueueRejected
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

data class EnterResponse(val ticket: Long?, val position: Long, val admitted: Boolean)
data class QueueErrorResponse(val code: String, val message: String)

@RestController
class QueueController(
    private val enterQueue: EnterQueueUseCase,
    private val leaveQueue: LeaveQueueUseCase,
    private val hub: QueueSseHub,
    @Value("\${ticket.product-id}") private val productId: Long,
) {
    @PostMapping("/api/queue/enter")
    fun enter(@RequestHeader("X-User-Id") userId: String): EnterResponse =
        enterQueue.enter(userId, productId).let { EnterResponse(it.ticket, it.position, it.admitted) }

    @DeleteMapping("/api/queue/leave")
    fun leave(@RequestHeader("X-User-Id") userId: String): ResponseEntity<Unit> {
        leaveQueue.leave(userId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/api/queue/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): SseEmitter = hub.subscribe()

    @ExceptionHandler(QueueRejected::class)
    fun rejected(e: QueueRejected): ResponseEntity<QueueErrorResponse> = when (e.reason) {
        QueueRejectReason.RATE_LIMITED -> error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청이 너무 많아요. 잠시 후 다시 시도해 주세요.")
        QueueRejectReason.NOT_OPEN -> error(HttpStatus.CONFLICT, "NOT_OPEN", "아직 오픈 전이에요.")
        QueueRejectReason.ALREADY_ORDERED -> error(HttpStatus.CONFLICT, "ALREADY_ORDERED", "이미 주문한 적이 있어요. 1인 1회만 주문할 수 있고, 취소된 주문도 포함돼요.")
        QueueRejectReason.SALE_ENDED -> error(HttpStatus.CONFLICT, "SALE_ENDED", "판매가 끝났어요.")
    }

    @ExceptionHandler(RedisConnectionFailureException::class)
    fun redisDown(): ResponseEntity<QueueErrorResponse> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "일시적인 장애가 있어요. 잠시 후 다시 시도해 주세요.")

    private fun error(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(QueueErrorResponse(code, message))
}
