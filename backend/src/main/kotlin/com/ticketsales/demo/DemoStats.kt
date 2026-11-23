package com.ticketsales.demo

import com.ticketsales.common.contract.CurrentRunContract
import com.ticketsales.outbox.OutboxRelay
import com.ticketsales.outbox.OutboxRepository
import com.ticketsales.payment.adapter.FakePgAdapter
import com.ticketsales.product.adapter.StockPortFactory
import com.ticketsales.queue.SaleOpening
import com.ticketsales.queue.SalesStatusQuery
import com.ticketsales.queue.adapter.QueueAdmissionScheduler
import com.ticketsales.queue.adapter.QueueSseHub
import com.ticketsales.queue.port.QueueStorePort
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import javax.sql.DataSource

data class StatsResponse(
    val runId: Long,
    val mode: String,
    val salesStatus: String,
    /** 오픈까지 남은 시간. 이미 오픈했으면 0 */
    val openInMs: Long,
    val total: Int,
    val remaining: Int,
    val pending: Long,
    val completed: Long,
    val cancelled: Long,
    /** 취소는 커밋됐지만 order.cancelled가 아직 처리되지 않아 재고가 돌아오지 않은 주문 수. 처리가 다 끝나면 0 */
    val awaitingRestore: Long,
    /** 취소되지 않은 주문이 전체 재고보다 많은 수. 안전한 방식에서는 항상 0이어야 한다. */
    val oversold: Long,
    /** 완료 + 결제 대기 + 복구 대기 취소 + 남은 재고 = 전체 재고 */
    val consistent: Boolean,
    val waiting: Long,
    val cursor: Long,
    val sseConnections: Int,
    val admissionRate: Int,
    val outboxUnpublished: Long,
    /** DLQ로 갔지만 아직 재처리하지 않은 메시지 수 */
    val dlqWaiting: Long,
    val duplicatesBlocked: Long,
    val staleSkipped: Long,
    val paidAfterCancel: Long,
    val optimisticRetries: Double,
    val kafkaPaused: Boolean,
    val pgFailureRate: Double,
    val pgDelayMs: Long,
    /** "PG 장애"가 켜져 있는지. 화면이 새로 고쳐져도 켜진 장애를 알 수 있게 서버 상태로 알려 준다. */
    val pgDown: Boolean,
    val poolActive: Int,
    val poolMax: Int,
    /** 가상 손님: IDLE(대기) / REQUESTED(실행기가 아직 안 받음) / RUNNING / DONE */
    val crowdState: String,
    val crowdUsers: Int,
    val crowdArrivalSeconds: Int,
    /** 실행기의 확인 요청이 최근 3초 안에 있었는지 */
    val crowdRunnerOnline: Boolean,
)

/** 관제·측정용 현황. 읽기 전용 집계라 여러 도메인의 데이터를 함께 본다(demo는 의존 방향의 맨 위). */
@Component
class DemoStats(
    private val runs: CurrentRunContract,
    private val stocks: StockPortFactory,
    private val jdbc: JdbcTemplate,
    private val outbox: OutboxRepository,
    private val registry: MeterRegistry,
    private val salesStatus: SalesStatusQuery,
    private val queue: QueueStorePort,
    private val queueHub: QueueSseHub,
    private val admission: QueueAdmissionScheduler,
    private val relay: OutboxRelay,
    private val pg: FakePgAdapter,
    private val dataSource: DataSource,
    private val crowd: CrowdControl,
    private val opening: SaleOpening,
    @Value("\${ticket.product-id}") private val productId: Long,
) {
    fun current(): StatsResponse {
        val counts = jdbc.queryForList(
            "SELECT status, COUNT(*) AS n FROM orders WHERE product_id = ? GROUP BY status", productId,
        ).associate { it["status"] as String to (it["n"] as Number).toLong() }
        val pending = counts["PENDING"] ?: 0
        val completed = counts["COMPLETED"] ?: 0
        val cancelled = counts["CANCELLED"] ?: 0
        val total = stocks.total(productId)
        val remaining = stocks.remaining(productId)
        val awaitingRestore = (cancelled - stocks.restoredCount(productId)).coerceAtLeast(0)
        val pool = dataSource.unwrap(HikariDataSource::class.java)
        val crowdRequest = crowd.request()
        return StatsResponse(
            runId = runs.current(),
            mode = stocks.mode.name,
            salesStatus = salesStatus.current(productId).name,
            openInMs = opening.openIn().toMillis(),
            total = total,
            remaining = remaining,
            pending = pending,
            completed = completed,
            cancelled = cancelled,
            awaitingRestore = awaitingRestore,
            oversold = (pending + completed - total).coerceAtLeast(0),
            consistent = completed + pending + awaitingRestore + remaining == total.toLong(),
            waiting = queue.waitingCount(),
            cursor = queue.cursor(),
            sseConnections = queueHub.connectionCount(),
            admissionRate = admission.admissionRate,
            outboxUnpublished = outbox.countByPublishedAtIsNull(),
            dlqWaiting = (sum("ticket.dlq.published") - sum("ticket.dlq.replayed")).toLong().coerceAtLeast(0),
            duplicatesBlocked = sum("ticket.consumer.skipped", "reason", "duplicate").toLong(),
            staleSkipped = sum("ticket.consumer.skipped", "reason", "stale_run").toLong(),
            paidAfterCancel = sum("ticket.orders.paid_after_cancel").toLong(),
            optimisticRetries = sum("ticket.stock.optimistic.retry"),
            kafkaPaused = relay.paused,
            pgFailureRate = pg.failureRate,
            pgDelayMs = pg.delayMs,
            pgDown = pg.transientErrors.get() > 0,
            poolActive = pool.hikariPoolMXBean?.activeConnections ?: 0,
            poolMax = pool.hikariConfigMXBean.maximumPoolSize,
            crowdState = crowd.state().name,
            crowdUsers = crowdRequest?.users ?: 0,
            crowdArrivalSeconds = crowdRequest?.arrivalSeconds ?: 0,
            crowdRunnerOnline = crowd.runnerOnline(),
        )
    }

    private fun sum(name: String, vararg tags: String): Double =
        registry.find(name).tags(*tags).counters().sumOf { it.count() }
}
