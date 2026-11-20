package com.ticketsales.product.adapter

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** 지금 고른 방식의 저장소 기준 남은 재고 */
@Component
class StockMetrics(
    stocks: StockPortFactory,
    registry: MeterRegistry,
    @Value("\${ticket.product-id}") productId: Long,
) {
    init {
        Gauge.builder("ticket.stock.remaining") { stocks.remaining(productId).toDouble() }
            .description("남은 재고")
            .register(registry)
    }
}
