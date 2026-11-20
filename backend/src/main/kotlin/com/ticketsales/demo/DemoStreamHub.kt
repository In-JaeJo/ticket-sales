package com.ticketsales.demo

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 관제 화면용 SSE. 0.5초마다 현황(stats)을 보내고, 직전 현황과 비교해 사람이 읽을 로그를 만든다.
 * 연결 직후에는 snapshot(현황 + 최근 로그 20줄)을 보낸다.
 */
@Component
class DemoStreamHub(private val stats: DemoStats) {
    private val emitters = CopyOnWriteArrayList<SseEmitter>()
    private val recentLogs = ArrayDeque<String>()

    @Volatile
    private var last: StatsResponse? = null

    /** Outbox 적체를 로그로 알렸는지. 평소 주문도 0.1초씩 Outbox에 머물므로 적체일 때만 알린다. */
    @Volatile
    private var backlogReported = false

    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(TIMEOUT_MS)
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        emitter.onError { emitters.remove(emitter) }
        emitters.add(emitter)
        val current = stats.current()
        if (last == null) last = current
        send(emitter, "snapshot", mapOf("stats" to current, "logs" to synchronized(recentLogs) { recentLogs.toList() }))
        return emitter
    }

    @Scheduled(fixedRate = 500)
    fun broadcast() {
        if (emitters.isEmpty()) return
        val now = stats.current()
        val before = last
        last = now
        emitters.forEach { send(it, "stats", now) }
        if (before == null) return
        describe(before, now).forEach(::log)
        reportBacklog(now)
    }

    @Scheduled(fixedRate = 15_000)
    fun heartbeat() {
        emitters.forEach { emitter ->
            runCatching { emitter.send(SseEmitter.event().comment("ping")) }.onFailure { emitters.remove(emitter) }
        }
    }

    private fun reportBacklog(now: StatsResponse) {
        if (!backlogReported && now.outboxUnpublished > 0 && (now.kafkaPaused || now.outboxUnpublished >= BACKLOG)) {
            backlogReported = true
            log("Outbox에 쌓이는 중: ${now.outboxUnpublished}건 (Kafka로 아직 못 보냄)")
        } else if (backlogReported && now.outboxUnpublished == 0L) {
            backlogReported = false
            log("Outbox 미발행 0 — 쌓인 이벤트 모두 발행, 유실 없음")
        }
    }

    private fun log(message: String) {
        val line = "${LocalTime.now().format(TIME)} $message"
        synchronized(recentLogs) {
            recentLogs.addLast(line)
            while (recentLogs.size > MAX_LOGS) recentLogs.removeFirst()
        }
        emitters.forEach { send(it, "log", line) }
    }

    private fun send(emitter: SseEmitter, name: String, data: Any) {
        runCatching { emitter.send(SseEmitter.event().name(name).data(data)) }.onFailure { emitters.remove(emitter) }
    }

    companion object {
        const val TIMEOUT_MS = 30 * 60 * 1000L
        const val MAX_LOGS = 20
        const val BACKLOG = 10
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        private val STATUS = mapOf("ON_SALE" to "판매 중", "SOLD_OUT" to "매진 (취소표 대기)", "FINAL_SOLD_OUT" to "최종 매진")

        /** 직전 현황과 비교해 바뀐 것을 로그 문장으로 */
        fun describe(a: StatsResponse, b: StatsResponse): List<String> {
            if (a.runId != b.runId) return listOf("리셋: ${b.mode} 방식, 재고 ${b.total}장")
            val lines = mutableListOf<String>()
            if (b.completed > a.completed) lines += "결제 완료 +${b.completed - a.completed} (누적 ${b.completed})"
            if (b.cancelled > a.cancelled) lines += "주문 취소 +${b.cancelled - a.cancelled} → 재고 복구"
            if (b.salesStatus != a.salesStatus) lines += "판매 상태: ${STATUS[b.salesStatus] ?: b.salesStatus}"
            if (b.kafkaPaused != a.kafkaPaused) lines += if (b.kafkaPaused) "Kafka 발행 중단" else "Kafka 발행 재개"
            if (b.duplicatesBlocked > a.duplicatesBlocked) lines += "중복 메시지 차단 +${b.duplicatesBlocked - a.duplicatesBlocked}"
            if (b.dlqWaiting > a.dlqWaiting) lines += "DLQ로 이동 +${b.dlqWaiting - a.dlqWaiting} (재시도 3번 실패)"
            if (b.dlqWaiting < a.dlqWaiting) lines += "DLQ 재처리 ${a.dlqWaiting - b.dlqWaiting}건"
            if (b.paidAfterCancel > a.paidAfterCancel) lines += "결제 후 취소 +${b.paidAfterCancel - a.paidAfterCancel}"
            if (b.oversold > a.oversold) lines += "초과 판매 발생: ${b.oversold}장"
            return lines
        }
    }
}
