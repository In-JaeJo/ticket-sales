package com.ticketsales.order

import com.ticketsales.common.contract.CurrentRunContract
import com.ticketsales.common.contract.DeductResult
import com.ticketsales.common.contract.QueuePassContract
import com.ticketsales.common.contract.StockDeductionContract
import com.ticketsales.common.event.DomainEvent
import com.ticketsales.order.port.OrderEventPort
import com.ticketsales.order.port.OrderMetricsPort
import com.ticketsales.order.port.OrderStorePort
import com.ticketsales.order.port.PricingPort
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 컨테이너 없이 도는 단위 테스트. Redis 복구가 실패하는 상황은 통합 테스트로 만들기 어렵다. */
class PlaceOrderUseCaseTest {

    private val redisStock = object : StockDeductionContract {
        override val transactional = false
        override fun deduct(productId: Long, userId: String) = DeductResult.SUCCESS
        override fun restoreAfterFailedOrder(productId: Long, userId: String) = error("Redis 순단")
    }

    private val unusedOrders = object : OrderStorePort {
        override fun save(order: Order) = order
        override fun findById(orderId: Long): Order? = null
        override fun findByIdForUpdate(orderId: Long): Order? = null
        override fun findPendingIdsCreatedBefore(before: Instant, limit: Int) = emptyList<Long>()
    }

    private val failingWriter = object : OrderWriter(
        redisStock,
        unusedOrders,
        object : OrderEventPort { override fun publish(topic: String, runId: Long, event: DomainEvent) = Unit },
        object : PricingPort { override fun price(productId: Long) = 0L },
        Clock.systemUTC(),
    ) {
        override fun place(userId: String, productId: Long, runId: Long, deductInside: Boolean): Long =
            throw IllegalStateException("주문 저장 실패")
    }

    private val useCase = PlaceOrderUseCase(
        passes = object : QueuePassContract { override fun consume(userId: String) = true },
        stock = redisStock,
        runs = object : CurrentRunContract { override fun current() = 1L },
        writer = failingWriter,
        metrics = object : OrderMetricsPort {
            override fun optimisticRetry() = Unit
            override fun paidAfterCancel() = Unit
        },
    )

    @Test
    fun `저장 실패 뒤 재고 복구까지 실패해도 원래 예외가 나가고 복구 실패는 덧붙는다`() {
        val e = assertFailsWith<IllegalStateException> { useCase.place("u1", 1L) }
        assertEquals("주문 저장 실패", e.message)
        assertEquals("Redis 순단", e.suppressed.single().message)
    }
}
