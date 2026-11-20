package com.ticketsales.order.port

import com.ticketsales.common.event.DomainEvent
import com.ticketsales.order.Order
import java.time.Instant

interface OrderStorePort {
    fun save(order: Order): Order
    fun findById(orderId: Long): Order?
    /** 상태를 바꾸려고 읽을 때. 행을 잠가 같은 주문을 동시에 바꾸는 요청을 줄 세운다. 트랜잭션 안에서만 부른다. */
    fun findByIdForUpdate(orderId: Long): Order?
    fun findPendingIdsCreatedBefore(before: Instant, limit: Int): List<Long>
}

/** 이벤트 발행. 구현은 Outbox에 기록한다. */
interface OrderEventPort {
    fun publish(topic: String, runId: Long, event: DomainEvent)
}

interface PricingPort {
    fun price(productId: Long): Long
}

/** 주문 쪽 지표. 도메인 핵심은 Micrometer를 모르므로 Port로 기록한다. */
interface OrderMetricsPort {
    fun optimisticRetry()
    fun paidAfterCancel()
}
