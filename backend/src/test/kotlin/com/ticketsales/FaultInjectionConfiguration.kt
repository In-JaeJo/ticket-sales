package com.ticketsales

import com.ticketsales.common.event.DomainEvent
import com.ticketsales.order.adapter.OrderOutboxEventAdapter
import com.ticketsales.order.port.OrderEventPort
import com.ticketsales.payment.adapter.PaymentOutboxEventAdapter
import com.ticketsales.payment.port.PaymentEventPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.atomic.AtomicInteger

/** 테스트에서 Outbox 기록을 정해진 횟수만큼 실패시킨다. 평소에는 그대로 위임한다. */
class Faults {
    val orderEventFailures = AtomicInteger(0)
    val paymentEventFailures = AtomicInteger(0)
    fun clear() { orderEventFailures.set(0); paymentEventFailures.set(0) }
}

private fun AtomicInteger.takeOne() = getAndUpdate { if (it > 0) it - 1 else 0 } > 0

@TestConfiguration(proxyBeanMethods = false)
class FaultInjectionConfiguration {
    @Bean
    fun faults() = Faults()

    @Bean
    @Primary
    fun faultyOrderEvents(delegate: OrderOutboxEventAdapter, faults: Faults) = object : OrderEventPort {
        override fun publish(topic: String, runId: Long, event: DomainEvent) {
            if (faults.orderEventFailures.takeOne()) error("Outbox 기록 실패 (테스트 주입)")
            delegate.publish(topic, runId, event)
        }
    }

    @Bean
    @Primary
    fun faultyPaymentEvents(delegate: PaymentOutboxEventAdapter, faults: Faults) = object : PaymentEventPort {
        override fun publish(topic: String, runId: Long, event: DomainEvent) {
            if (faults.paymentEventFailures.takeOne()) error("Outbox 기록 실패 (테스트 주입)")
            delegate.publish(topic, runId, event)
        }
    }
}
