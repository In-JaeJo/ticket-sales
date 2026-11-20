package com.ticketsales.queue.adapter

import com.ticketsales.queue.port.SaleSchedulePort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/** 오픈 시각을 Redis에 epoch 밀리초로 둔다. 리셋이 키를 지우면 바로 오픈 상태가 된다. */
@Component
class RedisSaleScheduleAdapter(private val redis: StringRedisTemplate) : SaleSchedulePort {
    override fun openAt(): Instant? = redis.opsForValue().get(KEY)?.let { Instant.ofEpochMilli(it.toLong()) }

    override fun setOpenAt(at: Instant) {
        redis.opsForValue().set(KEY, at.toEpochMilli().toString())
    }

    companion object {
        const val KEY = "sale:open-at"
    }
}
