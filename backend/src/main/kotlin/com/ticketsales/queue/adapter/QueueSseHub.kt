package com.ticketsales.queue.adapter

import com.ticketsales.common.contract.CurrentRunContract
import com.ticketsales.queue.SalesStatus
import com.ticketsales.queue.SalesStatusQuery
import com.ticketsales.queue.SaleOpening
import com.ticketsales.queue.port.QueueStorePort
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList

/** openInMs: 오픈까지 남은 시간. 화면은 이 값으로 카운트다운한다(손님 PC 시계와 무관). */
data class QueueSnapshot(val cursor: Long, val status: SalesStatus, val runId: Long, val openInMs: Long)

/**
 * 대기열 SSE. 개인 순번 대신 커서 숫자 하나만 0.2초마다 묶어 전체에 보낸다.
 * 바뀐 것만 보낸다: cursor / status / reset(새 판의 snapshot). 판매 전 → 판매 중도 status로 알린다. 연결 직후엔 snapshot, 15초마다 heartbeat.
 */
@Component
class QueueSseHub(
    private val queue: QueueStorePort,
    private val status: SalesStatusQuery,
    private val runs: CurrentRunContract,
    private val opening: SaleOpening,
    @Value("\${ticket.product-id}") private val productId: Long,
    registry: MeterRegistry,
) {
    private val emitters = CopyOnWriteArrayList<SseEmitter>()

    init {
        Gauge.builder("ticket.sse.connections") { emitters.size.toDouble() }.description("대기열 SSE 연결 수").register(registry)
        Gauge.builder("ticket.queue.waiting") { queue.waitingCount().toDouble() }.description("대기자 수").register(registry)
        Gauge.builder("ticket.queue.cursor") { queue.cursor().toDouble() }.description("입장 완료 지점").register(registry)
    }

    @Volatile
    private var last: QueueSnapshot? = null

    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(TIMEOUT_MS)
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        emitter.onError { emitters.remove(emitter) }
        emitters.add(emitter)
        val current = snapshot()
        send(emitter, "snapshot", current)
        // 아직 비교 기준이 없으면 방금 보낸 snapshot을 기준으로 삼는다.
        // 그러지 않으면 연결 직후 첫 방송까지 사이에 바뀐 상태가 알림 없이 사라진다.
        if (last == null) last = current
        return emitter
    }

    fun connectionCount(): Int = emitters.size

    @Scheduled(fixedRate = 200)
    fun broadcast() {
        if (emitters.isEmpty()) return
        val now = snapshot()
        val before = last
        last = now
        if (before == null) return
        if (now.runId != before.runId) emitters.forEach { send(it, "reset", now) }
        if (now.cursor != before.cursor) emitters.forEach { send(it, "cursor", mapOf("cursor" to now.cursor)) }
        if (now.status != before.status) emitters.forEach { send(it, "status", mapOf("status" to now.status)) }
    }

    @Scheduled(fixedRate = 15_000)
    fun heartbeat() {
        emitters.forEach { emitter ->
            runCatching { emitter.send(SseEmitter.event().comment("ping")) }.onFailure { emitters.remove(emitter) }
        }
    }

    private fun snapshot() = QueueSnapshot(queue.cursor(), status.current(productId), runs.current(), opening.openIn().toMillis())

    private fun send(emitter: SseEmitter, name: String, data: Any) {
        runCatching { emitter.send(SseEmitter.event().name(name).data(data)) }.onFailure { emitters.remove(emitter) }
    }

    companion object {
        const val TIMEOUT_MS = 30 * 60 * 1000L
    }
}
