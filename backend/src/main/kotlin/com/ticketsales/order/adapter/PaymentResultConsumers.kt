package com.ticketsales.order.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketsales.common.consumer.BaseConsumer
import com.ticketsales.common.contract.CurrentRunContract
import com.ticketsales.common.event.PaymentCompleted
import com.ticketsales.common.event.PaymentFailed
import com.ticketsales.common.event.Topics
import com.ticketsales.order.CancelOrderUseCase
import com.ticketsales.order.CompleteOrderUseCase
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class PaymentCompletedConsumer(
    private val useCase: CompleteOrderUseCase,
    redis: StringRedisTemplate,
    runs: CurrentRunContract,
    mapper: ObjectMapper,
) : BaseConsumer<PaymentCompleted>(PaymentCompleted::class.java, redis, runs, mapper) {

    @KafkaListener(topics = [Topics.PAYMENT_COMPLETED], groupId = "order-completed")
    fun listen(record: ConsumerRecord<String, String>) = consume(record)

    override fun handle(event: PaymentCompleted, runId: Long) = useCase.complete(event.orderId)
}

@Component
class PaymentFailedConsumer(
    private val useCase: CancelOrderUseCase,
    redis: StringRedisTemplate,
    runs: CurrentRunContract,
    mapper: ObjectMapper,
) : BaseConsumer<PaymentFailed>(PaymentFailed::class.java, redis, runs, mapper) {

    @KafkaListener(topics = [Topics.PAYMENT_FAILED], groupId = "order-failed")
    fun listen(record: ConsumerRecord<String, String>) = consume(record)

    override fun handle(event: PaymentFailed, runId: Long) = useCase.cancel(event.orderId, event.reason)
}
