package com.ticketsales.payment.adapter

import com.ticketsales.common.event.DomainEvent
import com.ticketsales.outbox.OutboxAppender
import com.ticketsales.payment.port.PaymentEventPort
import org.springframework.stereotype.Component

@Component
class PaymentOutboxEventAdapter(private val outbox: OutboxAppender) : PaymentEventPort {
    override fun publish(topic: String, runId: Long, event: DomainEvent) = outbox.append(topic, runId, event)
}
