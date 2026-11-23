package com.ticketsales.product.adapter

import com.ticketsales.common.contract.DeductResult
import com.ticketsales.common.contract.StockDeductionContract
import com.ticketsales.common.contract.StockQueryContract
import com.ticketsales.product.DeductMode
import com.ticketsales.product.port.StockPort
import com.ticketsales.product.port.StockPortSelector
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/** 지금 고른 방식의 StockPort를 고른다. 방식 전환은 리셋과 함께만 한다(demo가 호출). */
@Component
class StockPortFactory(
    private val ports: List<StockPort>,
    private val redis: StringRedisTemplate,
) : StockPortSelector, StockDeductionContract, StockQueryContract {

    private val byMode = ports.associateBy { it.mode }

    @Volatile
    var mode: DeductMode = DeductMode.REDIS_LUA

    override fun current(): StockPort = byMode.getValue(mode)

    /** 리셋: 모든 저장소(Redis, MySQL)의 재고를 같은 값으로 맞추고, 이번 판의 전체 재고를 기록한다. */
    fun resetAll(productId: Long, quantity: Int) {
        ports.forEach { it.reset(productId, quantity) }
        redis.opsForValue().set(totalKey(productId), quantity.toString())
    }

    override val transactional: Boolean get() = current().transactional
    override fun deduct(productId: Long, userId: String): DeductResult = current().deduct(productId, userId)
    override fun restoreAfterFailedOrder(productId: Long, userId: String) = current().restoreAfterFailedOrder(productId, userId)
    override fun remaining(productId: Long): Int = current().remaining(productId)
    fun restoredCount(productId: Long): Long = current().restoredCount(productId)
    override fun total(productId: Long): Int = redis.opsForValue().get(totalKey(productId))?.toInt() ?: 0

    private fun totalKey(productId: Long) = "stock:total:$productId"
}
