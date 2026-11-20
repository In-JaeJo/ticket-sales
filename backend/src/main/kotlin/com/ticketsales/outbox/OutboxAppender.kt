package com.ticketsales.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketsales.common.event.DomainEvent
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/** 이벤트를 Outbox에 기록한다. 호출한 쪽의 트랜잭션에 함께 묶여, 상태 변경이 커밋되면 이벤트도 반드시 남는다. */
@Component
class OutboxAppender(
    private val repository: OutboxRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    fun append(topic: String, runId: Long, event: DomainEvent) {
        repository.save(
            OutboxMessage(
                messageId = UUID.randomUUID().toString(),
                runId = runId,
                topic = topic,
                messageKey = event.orderId.toString(),
                payload = mapper.writeValueAsString(event),
                createdAt = clock.instant(),
            ),
        )
    }
}
