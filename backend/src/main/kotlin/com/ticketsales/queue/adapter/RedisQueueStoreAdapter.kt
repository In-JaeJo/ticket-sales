package com.ticketsales.queue.adapter

import com.ticketsales.queue.port.QueueStorePort
import com.ticketsales.queue.port.Waiting
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

/** queue:seq(번호표), queue:waiting(대기자 Sorted Set, 점수 = 번호표), queue:cursor(입장 완료 지점) */
@Component
class RedisQueueStoreAdapter(private val redis: StringRedisTemplate) : QueueStorePort {

    override fun enter(userId: String): Long = redis.execute(ENTER, listOf(WAITING, SEQ), userId)

    override fun cursor(): Long = redis.opsForValue().get(CURSOR)?.toLong() ?: 0

    override fun pop(n: Int): List<Waiting> =
        redis.opsForZSet().popMin(WAITING, n.toLong()).orEmpty()
            .map { Waiting(it.value!!, it.score!!.toLong()) }
            .sortedBy { it.ticket }

    override fun leave(userId: String): Boolean = (redis.opsForZSet().remove(WAITING, userId) ?: 0) > 0

    override fun advanceCursor(to: Long) {
        redis.execute(ADVANCE, listOf(CURSOR), to.toString())
    }

    override fun waitingCount(): Long = redis.opsForZSet().zCard(WAITING) ?: 0

    companion object {
        private const val SEQ = "queue:seq"
        private const val WAITING = "queue:waiting"
        private const val CURSOR = "queue:cursor"

        /** 이미 줄 서 있으면 기존 번호표, 아니면 새 번호표 (원자적) */
        private val ENTER = RedisScript.of<Long>(
            """
            local existing = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if existing then return tonumber(existing) end
            local ticket = redis.call('INCR', KEYS[2])
            redis.call('ZADD', KEYS[1], ticket, ARGV[1])
            return ticket
            """.trimIndent(),
            Long::class.java,
        )

        private val ADVANCE = RedisScript.of<Long>(
            """
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            if tonumber(ARGV[1]) > current then redis.call('SET', KEYS[1], ARGV[1]) end
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }
}
