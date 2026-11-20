package archfixture.coreredis.queue

import org.springframework.data.redis.core.StringRedisTemplate

/** 위반: UseCase가 Redis 타입을 직접 쓴다. Redis는 Adapter에만 있어야 한다. */
class EnterQueueUseCase(private val redis: StringRedisTemplate) {
    fun next() = redis.opsForValue().increment("queue:seq")
}
