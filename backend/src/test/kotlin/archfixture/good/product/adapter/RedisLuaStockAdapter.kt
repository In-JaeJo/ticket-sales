package archfixture.good.product.adapter

import archfixture.good.common.contract.DeductResult
import archfixture.good.common.contract.StockDeductionContract
import archfixture.good.product.port.StockPort
import org.springframework.data.redis.core.StringRedisTemplate

/** Redis는 Adapter에서만 쓴다. */
class RedisLuaStockAdapter(private val redis: StringRedisTemplate) : StockPort {
    override val transactional = false

    override fun deduct(productId: Long, userId: String): DeductResult {
        val left = redis.opsForValue().decrement("stock:$productId") ?: -1
        return if (left >= 0) DeductResult.SUCCESS else DeductResult.SOLD_OUT
    }

    override fun restore(productId: Long, userId: String) {
        redis.opsForValue().increment("stock:$productId")
    }
}

/** common 계약의 구현은 그 자원을 가진 도메인의 Adapter에 둔다. */
class StockDeductionContractAdapter(private val port: StockPort) : StockDeductionContract {
    override fun deduct(productId: Long, userId: String) = port.deduct(productId, userId)
}
