package com.ticketsales.demo

import com.ticketsales.common.config.RedisRunAdapter
import com.ticketsales.common.consumer.MessageHeaders
import com.ticketsales.common.event.Topics
import com.ticketsales.outbox.OutboxRelay
import com.ticketsales.payment.adapter.FakePgAdapter
import com.ticketsales.product.DeductMode
import com.ticketsales.product.adapter.StockPortFactory
import com.ticketsales.queue.SaleOpening
import com.ticketsales.queue.adapter.QueueAdmissionScheduler
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.Metrics
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** 데모 제어. 운영 기능이 아니라 시연·측정을 위한 조작이다. */
@Service
class DemoService(
    private val runs: RedisRunAdapter,
    private val redis: StringRedisTemplate,
    private val jdbc: JdbcTemplate,
    private val stocks: StockPortFactory,
    private val pg: FakePgAdapter,
    private val relay: OutboxRelay,
    private val admission: QueueAdmissionScheduler,
    private val opening: SaleOpening,
    private val dataSource: DataSource,
    private val consumers: ConsumerFactory<String, String>,
    private val kafka: KafkaTemplate<String, String>,
    @Value("\${ticket.product-id}") private val productId: Long,
    @Value("\${ticket.initial-stock}") initialStock: Int,
) {
    @Volatile
    var initialStock: Int = initialStock

    /**
     * 리셋: 판 번호를 먼저 올려 Kafka에 남은 이전 판 메시지가 걸러지게 하고,
     * 나머지 Redis 키 전부와 MySQL 주문·Outbox를 비운 뒤 모든 저장소의 재고를 같은 값으로 맞춘다.
     * 가상 손님 투입 요청도 지워져 실행기가 멈춘다. 실행기가 살아 있다는 표시는 데이터가 아니라 남긴다.
     * openIn을 주면 그만큼 뒤에 오픈하고(판매 전), 주지 않으면 바로 오픈한다. 오픈 시각을 돌려준다.
     */
    fun reset(openIn: Duration = Duration.ZERO): Instant {
        runs.next()
        val keys = redis.keys("*").filter { it != RedisRunAdapter.KEY && it != CrowdControl.RUNNER }
        if (keys.isNotEmpty()) redis.delete(keys)
        val openAt = opening.scheduleAfter(openIn)
        jdbc.update("DELETE FROM outbox")
        jdbc.update("DELETE FROM orders")
        stocks.resetAll(productId, initialStock)
        pg.clear()
        return openAt
    }

    /** 방식 전환은 리셋과 함께만 한다. 판매 중에 바꾸면 Redis와 MySQL의 재고가 어긋난다. */
    fun changeMode(mode: DeductMode) {
        stocks.mode = mode
        reset()
    }

    fun mode(): DeductMode = stocks.mode

    fun configure(initialStock: Int?, admissionRate: Int?, poolSize: Int?) {
        initialStock?.let {
            require(it >= 0) { "재고 수는 0 이상이어야 해요." }
            this.initialStock = it
        }
        admissionRate?.let {
            require(it >= 0) { "입장률은 0 이상이어야 해요." }
            admission.admissionRate = it
        }
        poolSize?.let {
            require(it in 1..100) { "커넥션 풀은 1~100 사이여야 해요." }
            dataSource.unwrap(HikariDataSource::class.java).hikariConfigMXBean.maximumPoolSize = it
        }
    }

    fun fault(kafkaPaused: Boolean?, duplicate: Boolean?, pgFailureRate: Double?, pgDelayMs: Long?, pgTransientErrors: Int?) {
        pgFailureRate?.let {
            require(it in 0.0..1.0) { "결제 실패율은 0~1 사이여야 해요." }
            pg.failureRate = it
        }
        pgDelayMs?.let {
            require(it in 0..MAX_PG_DELAY_MS) { "결제 지연은 0~${MAX_PG_DELAY_MS}ms 사이여야 해요." }
            pg.delayMs = it
        }
        pgTransientErrors?.let { pg.transientErrors.set(it) }
        kafkaPaused?.let { relay.paused = it }
        if (duplicate == true) relay.republishLatest()
    }

    /**
     * DLQ 재처리: 각 {토픽}.DLT에서 아직 재처리하지 않은 메시지를 원래 토픽으로 같은 메시지 ID·판 번호로 다시 보낸다.
     * DLQ에 있다는 것은 처리가 실패했다는 뜻이다. 실패할 때 멱등 키를 지우지 못했으면(Redis 장애) "처리 중" 표시가 남아
     * 다시 보낸 메시지가 중복으로 건너뛰어지므로, 보내기 전에 남은 "처리 중" 표시를 지운다. "완료"는 그대로 둔다.
     */
    fun replayDlq(): Int {
        var replayed = 0
        consumers.createConsumer("dlq-replay", "dlq-replay").use { consumer ->
            val partitions = Topics.ALL.flatMap { topic ->
                consumer.partitionsFor("$topic.DLT").map { TopicPartition("$topic.DLT", it.partition()) }
            }
            consumer.assign(partitions)
            val committed = consumer.committed(partitions.toSet())
            partitions.forEach { p ->
                committed[p]?.let { consumer.seek(p, it.offset()) } ?: consumer.seekToBeginning(listOf(p))
            }
            val end = consumer.endOffsets(partitions)
            val deadline = System.currentTimeMillis() + 10_000
            while (partitions.any { consumer.position(it) < end.getValue(it) } && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach { record ->
                    val retry = ProducerRecord(record.topic().removeSuffix(".DLT"), record.key(), record.value())
                    record.headers()
                        .filter { it.key() == MessageHeaders.MESSAGE_ID || it.key() == MessageHeaders.RUN_ID }
                        .forEach { retry.headers().add(it) }
                    record.headers().lastHeader(MessageHeaders.MESSAGE_ID)?.let { clearStaleProcessing(String(it.value())) }
                    kafka.send(retry).get(5, TimeUnit.SECONDS)
                    Metrics.counter("ticket.dlq.replayed", "topic", record.topic().removeSuffix(".DLT")).increment()
                    replayed++
                }
            }
            consumer.commitSync(partitions.associateWith { OffsetAndMetadata(consumer.position(it)) })
        }
        return replayed
    }

    private fun clearStaleProcessing(messageId: String) {
        val key = "processed:$messageId"
        if (redis.opsForValue().get(key)?.startsWith("processing:") == true) redis.delete(key)
    }

    companion object {
        /** 결제 소비자가 쌓인 결제를 결제 대기 시간(5분) 안에 처리하도록 낮게 둔다. 계산은 ARCHITECTURE 3.3 */
        const val MAX_PG_DELAY_MS = 2_000L
    }
}
