package com.ticketsales.product.adapter

import com.ticketsales.common.contract.DeductResult
import com.ticketsales.product.DeductMode
import com.ticketsales.product.port.StockPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

@Component
class RedisLuaStockAdapter(private val redis: StringRedisTemplate) : StockPort {
    override val mode = DeductMode.REDIS_LUA
    override val transactional = false

    override fun deduct(productId: Long, userId: String): DeductResult =
        when (redis.execute(DEDUCT, keys(productId), userId)) {
            1L -> DeductResult.SUCCESS
            -1L -> DeductResult.DUPLICATE
            else -> DeductResult.SOLD_OUT
        }

    override fun restoreAfterFailedOrder(productId: Long, userId: String) {
        redis.execute(RESTORE_FAILED_ORDER, keys(productId), userId)
    }

    override fun restoreAfterCancel(productId: Long, orderId: Long) {
        redis.execute(RESTORE_CANCELLED, listOf(stockKey(productId), restoredKey(productId)), orderId.toString())
    }

    override fun reset(productId: Long, quantity: Int) {
        redis.opsForValue().set(stockKey(productId), quantity.toString())
        redis.delete(listOf(boughtKey(productId), restoredKey(productId)))
    }

    override fun restoredCount(productId: Long): Long = redis.opsForSet().size(restoredKey(productId)) ?: 0

    override fun remaining(productId: Long): Int = redis.opsForValue().get(stockKey(productId))?.toInt() ?: 0

    private fun stockKey(productId: Long) = "stock:$productId"
    private fun boughtKey(productId: Long) = "bought:$productId"
    private fun restoredKey(productId: Long) = "stock:restored:$productId"
    private fun keys(productId: Long) = listOf(stockKey(productId), boughtKey(productId))

    companion object {
        private val DEDUCT = RedisScript.of<Long>(
            """
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end
            local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
            if stock <= 0 then return -2 end
            redis.call('DECR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /** 구매 이력이 있던 경우에만 되돌린다. 같은 복구가 두 번 와도 재고가 두 번 늘지 않는다. */
        private val RESTORE_FAILED_ORDER = RedisScript.of<Long>(
            """
            if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
              redis.call('INCR', KEYS[1])
              return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )

        /** 되돌린 주문 ID 집합에 처음 들어갈 때만 재고를 늘린다. 같은 취소가 다시 처리돼도 한 번만 늘어난다. */
        private val RESTORE_CANCELLED = RedisScript.of<Long>(
            """
            if redis.call('SADD', KEYS[2], ARGV[1]) == 1 then
              redis.call('INCR', KEYS[1])
              return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
