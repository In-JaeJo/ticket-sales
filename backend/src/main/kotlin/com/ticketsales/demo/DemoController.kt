package com.ticketsales.demo

import com.ticketsales.product.DeductMode
import com.ticketsales.queue.GrantPassUseCase
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

data class GrantPassRequest(val userIds: List<String>)
data class GrantPassResponse(val granted: Int)
data class ModeRequest(val mode: DeductMode)
data class ConfigRequest(val initialStock: Int? = null, val admissionRate: Int? = null, val poolSize: Int? = null)
data class FaultRequest(
    val kafkaPaused: Boolean? = null,
    val duplicate: Boolean? = null,
    val pgFailureRate: Double? = null,
    val pgDelayMs: Long? = null,
    val pgTransientErrors: Int? = null,
)
data class ReplayResponse(val replayed: Int)
data class CrowdStartRequest(val users: Int, val arrivalSeconds: Int, val openInSeconds: Int = 0)
data class CrowdStateReport(val id: Long, val state: CrowdState)
data class DemoErrorResponse(val code: String, val message: String)

/** 데모 제어 API. 리소스가 아니라 동작 중심으로 의도적으로 분리했다. */
@RestController
class DemoController(
    private val demo: DemoService,
    private val grantPass: GrantPassUseCase,
    private val stats: DemoStats,
    private val streamHub: DemoStreamHub,
    private val crowd: CrowdControl,
) {
    /** 현황: 재고, 주문 상태, 초과 판매, 정합성, Outbox 미발행, 낙관적 락 재시도 */
    @GetMapping("/api/demo/stats")
    fun stats() = stats.current()

    /** 관제 화면용 SSE: snapshot, stats(0.5초마다), log */
    @GetMapping("/api/demo/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): SseEmitter = streamHub.subscribe()

    /** 방식 비교 측정용. 대기열을 거치지 않고 입장 토큰을 한 번에 발급한다. */
    @PostMapping("/api/demo/pass")
    fun grantPass(@RequestBody request: GrantPassRequest) = GrantPassResponse(grantPass.grantAll(request.userIds))

    @PostMapping("/api/demo/reset")
    fun reset(): ResponseEntity<Unit> {
        demo.reset()
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/demo/mode")
    fun mode(@RequestBody request: ModeRequest): ResponseEntity<Unit> {
        demo.changeMode(request.mode)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/demo/config")
    fun config(@RequestBody request: ConfigRequest): ResponseEntity<Unit> {
        demo.configure(request.initialStock, request.admissionRate, request.poolSize)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/demo/fault")
    fun fault(@RequestBody request: FaultRequest): ResponseEntity<Unit> {
        demo.fault(request.kafkaPaused, request.duplicate, request.pgFailureRate, request.pgDelayMs, request.pgTransientErrors)
        return ResponseEntity.noContent().build()
    }

    /** 설정에 적용된 재고·입장률과 오픈까지 N초로 리셋하고 가상 손님 투입을 요청한다. 실행기 컨테이너가 받아 k6를 띄운다. */
    @PostMapping("/api/demo/crowd")
    fun startCrowd(@RequestBody request: CrowdStartRequest): CrowdRequest = crowd.start(request.users, request.arrivalSeconds, request.openInSeconds)

    @PostMapping("/api/demo/crowd/stop")
    fun stopCrowd(): ResponseEntity<Unit> {
        crowd.stop()
        return ResponseEntity.noContent().build()
    }

    /** 실행기 전용: 지금 투입 요청. 없으면 204라 실행기는 k6를 멈춘다. */
    @GetMapping("/api/demo/crowd/request")
    fun crowdRequest(): ResponseEntity<CrowdRequest> =
        crowd.pollByRunner()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    /** 실행기 전용: k6를 띄웠으면 RUNNING, 끝났으면 DONE */
    @PostMapping("/api/demo/crowd/state")
    fun crowdState(@RequestBody report: CrowdStateReport): ResponseEntity<Unit> {
        crowd.report(report.id, report.state)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/demo/dlq/replay")
    fun replay() = ReplayResponse(demo.replayDlq())

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ResponseEntity<DemoErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(DemoErrorResponse("INVALID", e.message ?: "잘못된 값이에요."))
}
