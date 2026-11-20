package com.ticketsales.order.adapter

import com.ticketsales.order.OrderStatus
import com.ticketsales.order.port.OrderMetricsPort
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class MicrometerOrderMetrics(
    private val registry: MeterRegistry,
    repository: OrderJpaRepository,
    @Value("\${ticket.product-id}") productId: Long,
) : OrderMetricsPort {

    init {
        OrderStatus.entries.forEach { status ->
            Gauge.builder("ticket.orders") { repository.countByProductIdAndStatus(productId, status).toDouble() }
                .tag("status", status.name)
                .description("상태별 주문 수")
                .register(registry)
        }
    }

    override fun optimisticRetry() = registry.counter("ticket.stock.optimistic.retry").increment()

    override fun paidAfterCancel() = registry.counter("ticket.orders.paid_after_cancel").increment()
}
