package com.ticketsales.outbox

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** Outbox에 쌓여 아직 Kafka로 나가지 않은 메시지 수. Kafka 장애 데모에서 늘었다가 0으로 줄어든다. */
@Component
class OutboxMetrics(repository: OutboxRepository, registry: MeterRegistry) {
    init {
        Gauge.builder("ticket.outbox.unpublished") { repository.countByPublishedAtIsNull().toDouble() }
            .description("Outbox 미발행 메시지 수")
            .register(registry)
    }
}
