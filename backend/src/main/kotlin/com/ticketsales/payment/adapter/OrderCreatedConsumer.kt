package com.ticketsales.payment.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketsales.common.consumer.BaseConsumer
import com.ticketsales.common.contract.CurrentRunContract
import com.ticketsales.common.event.OrderCreated
import com.ticketsales.common.event.Topics
import com.ticketsales.payment.ProcessPaymentUseCase
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class OrderCreatedConsumer(
    private val useCase: ProcessPaymentUseCase,
    redis: StringRedisTemplate,
    runs: CurrentRunContract,
    mapper: ObjectMapper,
) : BaseConsumer<OrderCreated>(OrderCreated::class.java, redis, runs, mapper) {

    @KafkaListener(topics = [Topics.ORDER_CREATED], groupId = "payment")
    fun listen(record: ConsumerRecord<String, String>) = consume(record)

    override fun handle(event: OrderCreated, runId: Long) = useCase.process(event, runId)
}
