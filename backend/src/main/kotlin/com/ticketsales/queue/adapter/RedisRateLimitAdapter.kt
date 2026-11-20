package com.ticketsales.queue.adapter

import com.ticketsales.queue.port.RateLimitPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Clock

/** 사용자별 토큰 버킷. 오픈 직후 몰리는 요청(버스트)은 받아 주고 평균 속도는 지킨다. */
@Component
class RedisRateLimitAdapter(
    private val redis: StringRedisTemplate,
    private val clock: Clock,
    @Value("\${ticket.queue.rate-limit.capacity}") private val capacity: Int,
    @Value("\${ticket.queue.rate-limit.refill-per-second}") private val refillPerSecond: Double,
) : RateLimitPort {

    override fun tryAcquire(userId: String): Boolean =
        redis.execute(
            BUCKET, listOf("ratelimit:$userId"),
            capacity.toString(), refillPerSecond.toString(), clock.millis().toString(),
        ) == 1L

    companion object {
        private val BUCKET = RedisScript.of<Long>(
            """
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(bucket[1]) or capacity
            local ts = tonumber(bucket[2]) or now
            tokens = math.min(capacity, tokens + (now - ts) / 1000 * refill)
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], 60000)
            return allowed
            """.trimIndent(),
            Long::class.java,
        )
    }
}
