package com.ticketsales.product.adapter

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/** 앱이 처음 뜰 때 Redis 재고가 없으면 설정값으로 모든 저장소를 맞춘다. */
@Component
class StockInitializer(
    private val stocks: StockPortFactory,
    private val redis: StringRedisTemplate,
    @Value("\${ticket.product-id}") private val productId: Long,
    @Value("\${ticket.initial-stock}") private val initialStock: Int,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun init() {
        if (redis.hasKey("stock:$productId") != true) stocks.resetAll(productId, initialStock)
    }
}
