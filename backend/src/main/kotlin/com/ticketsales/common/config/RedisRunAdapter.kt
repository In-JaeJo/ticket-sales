package com.ticketsales.common.config

import com.ticketsales.common.contract.CurrentRunContract
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisRunAdapter(private val redis: StringRedisTemplate) : CurrentRunContract {
    override fun current(): Long {
        redis.opsForValue().setIfAbsent(KEY, "1")
        return redis.opsForValue().get(KEY)!!.toLong()
    }

    fun next(): Long = redis.opsForValue().increment(KEY)!!

    companion object {
        const val KEY = "run:current"
    }
}
