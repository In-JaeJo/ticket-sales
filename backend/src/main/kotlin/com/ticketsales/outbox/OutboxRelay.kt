package com.ticketsales.outbox

import com.ticketsales.common.consumer.MessageHeaders
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 0.1초마다 미발행 행을 id 순으로 Kafka에 보낸다.
 * 한 번에 최대 500건을 연달아 보내고(응답을 하나씩 기다리지 않는다) 응답을 모아 확인한 뒤, 발행 시각을 UPDATE 한 번으로 기록한다.
 * 다 보내지 못할 만큼 쌓여 있으면 쉬지 않고 다음 묶음을 이어서 보낸다.
 * 중간에 실패하면 성공한 앞부분만 발행으로 기록하고, 나머지는 다음 주기에 다시 보낸다(최소 1회 전달, 중복은 멱등 소비자가 거른다).
 * 실패한 뒤에는 1건씩만 보내 Kafka가 돌아왔는지 확인한다. 장애 중에 매 주기 묶음째 다시 보내 같은 메시지가 프로듀서 버퍼에 쌓이지 않게 한다.
 */
@Component
class OutboxRelay(
    private val repository: OutboxRepository,
    private val kafka: KafkaTemplate<String, String>,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 관제 화면의 "Kafka 발행 중단" */
    @Volatile
    var paused: Boolean = false

    /** 직전 발행이 실패했으면 1건씩 확인하며 보낸다 */
    @Volatile
    private var probing = false

    @Scheduled(fixedDelayString = "\${ticket.outbox.poll-ms:100}")
    fun relay() {
        repeat(MAX_BATCHES_PER_TICK) {
            if (paused) return
            val unpublished = repository.findTop500ByPublishedAtIsNullOrderByIdAsc()
            if (unpublished.isEmpty()) return
            val batch = if (probing) unpublished.take(1) else unpublished
            val sent = publish(batch)
            if (sent.isNotEmpty()) repository.markPublished(sent, clock.instant())
            probing = sent.size < batch.size
            when {
                probing -> return // 실패: 다음 주기에 1건으로 다시 확인
                batch.size < unpublished.size -> return@repeat // 확인 1건이 성공: 바로 묶음으로 이어서 보낸다
                unpublished.size < BATCH_SIZE -> return // 다 보냈다
            }
        }
    }

    /** 묶음을 연달아 보내고 응답을 순서대로 확인한다. 처음 실패한 곳 앞까지의 id를 돌려준다. */
    private fun publish(batch: List<OutboxMessage>): List<Long> {
        // 보내는 순간 실패하면(Kafka에 연결 못 함) 나머지는 보내지 않는다. 한 건씩 오래 막히는 것을 줄줄이 반복하지 않게
        val futures = ArrayList<CompletableFuture<SendResult<String, String>>>(batch.size)
        for (message in batch) {
            val future = send(message)
            futures += future
            if (future.isCompletedExceptionally) break
        }
        val sent = ArrayList<Long>(futures.size)
        for ((message, future) in batch.zip(futures)) {
            try {
                future.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                sent += message.id
            } catch (e: Exception) {
                log.warn("Outbox 발행 실패, 다음 주기에 다시 시도: {}", e.message)
                break
            }
        }
        return sent
    }

    /** 장애 주입 "중복 메시지": 가장 최근에 발행한 메시지를 같은 메시지 ID로 한 번 더 보낸다. */
    fun republishLatest(): Boolean {
        val latest = repository.findTopByPublishedAtIsNotNullOrderByIdDesc() ?: return false
        send(latest).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return true
    }

    private fun send(message: OutboxMessage): CompletableFuture<SendResult<String, String>> {
        val record = ProducerRecord(message.topic, message.messageKey, message.payload).apply {
            headers().add(MessageHeaders.MESSAGE_ID, message.messageId.toByteArray())
            headers().add(MessageHeaders.RUN_ID, message.runId.toString().toByteArray())
        }
        return try {
            kafka.send(record)
        } catch (e: Exception) {
            CompletableFuture.failedFuture(e)
        }
    }

    companion object {
        const val BATCH_SIZE = 500
        /** 한 주기에 이어서 보내는 최대 묶음 수. 쌓인 양이 많아도 한 주기가 끝없이 길어지지 않게 한다. */
        const val MAX_BATCHES_PER_TICK = 20
        const val SEND_TIMEOUT_SECONDS = 5L
    }
}
