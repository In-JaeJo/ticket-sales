package com.ticketsales.order.adapter

import com.ticketsales.common.event.DomainEvent
import com.ticketsales.order.port.OrderEventPort
import com.ticketsales.outbox.OutboxAppender
import org.springframework.stereotype.Component

@Component
class OrderOutboxEventAdapter(private val outbox: OutboxAppender) : OrderEventPort {
    override fun publish(topic: String, runId: Long, event: DomainEvent) = outbox.append(topic, runId, event)
}
