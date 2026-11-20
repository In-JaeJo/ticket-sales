package com.ticketsales.queue.adapter

import com.ticketsales.common.contract.QueuePassContract
import com.ticketsales.queue.port.PassStorePort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisPassStoreAdapter(private val redis: StringRedisTemplate) : PassStorePort, QueuePassContract {
    override fun grant(userId: String, ttl: Duration) {
        redis.opsForValue().set(key(userId), "1", ttl)
    }

    override fun consume(userId: String): Boolean = redis.opsForValue().getAndDelete(key(userId)) != null

    override fun has(userId: String): Boolean = redis.hasKey(key(userId)) == true

    override fun revoke(userId: String) {
        redis.delete(key(userId))
    }

    private fun key(userId: String) = "queue:pass:$userId"
}
