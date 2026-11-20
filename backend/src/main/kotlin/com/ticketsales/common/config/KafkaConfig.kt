package com.ticketsales.common.config

import com.ticketsales.common.event.Topics
import io.micrometer.core.instrument.Metrics
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries

@Configuration
class KafkaConfig {

    /** 이벤트마다 토픽 1개 + DLT. 파티션 3개, 키는 주문 ID */
    @Bean
    fun topics(): KafkaAdmin.NewTopics = KafkaAdmin.NewTopics(
        *Topics.ALL.flatMap { listOf(topic(it), topic("$it.DLT")) }.toTypedArray(),
    )

    /** 재시도 3회(지수 백오프), 그래도 실패하면 {토픽}.DLT */
    @Bean
    fun errorHandler(
        kafka: KafkaOperations<Any, Any>,
        @Value("\${ticket.retry.initial-interval-ms}") initialInterval: Long,
        @Value("\${ticket.retry.multiplier}") multiplier: Double,
    ): DefaultErrorHandler {
        val backOff = ExponentialBackOffWithMaxRetries(3).apply {
            this.initialInterval = initialInterval
            this.multiplier = multiplier
        }
        // Spring Kafka 3 기본 DLT 이름은 "{토픽}-dlt"다. 미리 만든 "{토픽}.DLT"로 같은 파티션에 보낸다.
        val dlt = DeadLetterPublishingRecoverer(kafka) { record, _ ->
            TopicPartition("${record.topic()}.DLT", record.partition())
        }
        // DLT 전송이 끝난 뒤에 센다. 먼저 세면 재처리 버튼이 아직 도착하지 않은 메시지를 놓친다.
        return DefaultErrorHandler({ record, exception ->
            dlt.accept(record, exception)
            Metrics.counter("ticket.dlq.published", "topic", record.topic()).increment()
        }, backOff)
    }

    private fun topic(name: String): NewTopic = TopicBuilder.name(name).partitions(3).replicas(1).build()
}
