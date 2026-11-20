package com.ticketsales.common.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketsales.common.contract.CurrentRunContract
import io.micrometer.core.instrument.Metrics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 모든 소비자의 공통 처리 (Template Method).
 * ① 판 번호 확인 → ② 이 앱에서 지금 같은 메시지를 처리 중이면 중복 → ③ 멱등 키(Outbox 메시지 ID)를 "처리 중"으로 잡음
 * → ④ 처리 → ⑤ 성공하면 "완료", 실패하면 키를 지우고 다시 던짐.
 * 실패할 때 키를 남기면 재시도가 "이미 처리됨"으로 버려져 메시지가 사라진다.
 * 그래도 키가 남는 경우가 있다(처리 도중 앱이 죽음, 키를 지우는 순간 Redis 순단). "지금 처리 중인가"는 앱 메모리로 판단하고,
 * Redis에 "처리 중"이 남아 있어도 지금 처리하는 스레드가 없으면 실패한 흔적으로 보고 이어받아 다시 처리한다.
 * (앱은 1개 인스턴스로 운영한다는 전제. 여러 대면 살아 있는 다른 인스턴스와 구분할 수 없다)
 * 재시도와 DLQ는 Kafka 에러 핸들러가 맡는다.
 */
abstract class BaseConsumer<T : Any>(
    private val type: Class<T>,
    private val redis: StringRedisTemplate,
    private val runs: CurrentRunContract,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    protected abstract fun handle(event: T, runId: Long)

    fun consume(record: ConsumerRecord<String, String>) {
        val runId = header(record, MessageHeaders.RUN_ID)?.toLong()
        if (runId == null || runId != runs.current()) {
            log.info("지금 판이 아닌 메시지라 건너뜀: topic={} run={}", record.topic(), runId)
            Metrics.counter("ticket.consumer.skipped", "reason", "stale_run", "topic", record.topic()).increment()
            return
        }
        val messageId = header(record, MessageHeaders.MESSAGE_ID) ?: error("메시지 ID 없음: ${record.topic()}")
        // 리밸런스로 같은 메시지가 이 앱의 다른 스레드에 다시 배정된 경우
        if (!inFlight.add(messageId)) return skipDuplicate(record, messageId)
        try {
            consumeClaimed(record, messageId, runId)
        } finally {
            inFlight.remove(messageId)
        }
    }

    private fun consumeClaimed(record: ConsumerRecord<String, String>, messageId: String, runId: Long) {
        val key = "processed:$messageId"
        when (redis.execute(CLAIM, listOf(key), PROCESSING, DONE, TTL.seconds.toString())) {
            CLAIMED -> Unit
            TAKEN_OVER -> {
                log.warn("처리하다 멈추거나 실패하고 남은 \"처리 중\" 표시를 이어받아 다시 처리: {}", messageId)
                Metrics.counter("ticket.consumer.taken_over", "topic", record.topic()).increment()
            }
            else -> return skipDuplicate(record, messageId)
        }
        try {
            handle(mapper.readValue(record.value(), type), runId)
        } catch (e: Exception) {
            Metrics.counter("ticket.consumer.failed", "topic", record.topic()).increment()
            // 키를 지우지 못해도(Redis 순단) 원래 예외를 던진다. 남은 "처리 중" 표시는 다음 재시도가 이어받는다
            runCatching { redis.delete(key) }.onFailure { e.addSuppressed(it) }
            throw e
        }
        redis.opsForValue().set(key, DONE, TTL)
    }

    private fun skipDuplicate(record: ConsumerRecord<String, String>, messageId: String) {
        log.info("이미 처리했거나 처리 중인 메시지라 건너뜀: {}", messageId)
        Metrics.counter("ticket.consumer.skipped", "reason", "duplicate", "topic", record.topic()).increment()
    }

    private fun header(record: ConsumerRecord<String, String>, name: String): String? =
        record.headers().lastHeader(name)?.value()?.let { String(it) }

    companion object {
        val TTL: Duration = Duration.ofHours(24)
        const val DONE = "done"

        /** 이 앱에서 지금 처리하고 있는 메시지 ID. "지금 처리 중인가"는 Redis가 아니라 이것으로 판단한다. */
        private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** 이 프로세스가 처리 중이라는 표시. 앱을 띄울 때마다 달라진다. */
        internal val PROCESSING = "processing:${UUID.randomUUID()}"

        private const val CLAIMED = 1L
        private const val TAKEN_OVER = 2L

        /**
         * 멱등 키 잡기 (원자적).
         * 없음 → 처리 중으로 잡음(1) / 완료 → 중복(0) / "처리 중"이 남아 있음 → 이어받음(2).
         * 지금 처리 중인 메시지는 이 스크립트를 부르기 전에 앱 메모리(inFlight)에서 걸러지므로, 여기서 만나는 "처리 중"은 남은 흔적이다.
         */
        private val CLAIM = RedisScript.of<Long>(
            """
            local value = redis.call('GET', KEYS[1])
            if not value then
              redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
              return 1
            end
            if value == ARGV[2] then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
            return 2
            """.trimIndent(),
            Long::class.java,
        )
    }
}
