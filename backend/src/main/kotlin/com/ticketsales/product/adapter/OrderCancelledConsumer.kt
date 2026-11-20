package com.ticketsales.product.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketsales.common.consumer.BaseConsumer
import com.ticketsales.common.contract.CurrentRunContract
import com.ticketsales.common.event.OrderCancelled
import com.ticketsales.common.event.Topics
import com.ticketsales.product.RestoreStockUseCase
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class OrderCancelledConsumer(
    private val useCase: RestoreStockUseCase,
    redis: StringRedisTemplate,
    runs: CurrentRunContract,
    mapper: ObjectMapper,
) : BaseConsumer<OrderCancelled>(OrderCancelled::class.java, redis, runs, mapper) {

    @KafkaListener(topics = [Topics.ORDER_CANCELLED], groupId = "product")
    fun listen(record: ConsumerRecord<String, String>) = consume(record)

    override fun handle(event: OrderCancelled, runId: Long) = useCase.restore(event)
}
