package com.ticketsales.product.adapter

import com.ticketsales.common.contract.DeductResult
import com.ticketsales.common.contract.StockConflict
import com.ticketsales.product.DeductMode
import com.ticketsales.product.port.StockPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * MySQL `stock` 테이블을 쓰는 방식들의 공통 부분.
 * 주문 트랜잭션 안에서 실행되므로 실패하면 롤백으로 재고가 자동 복구된다.
 */
abstract class JdbcStockAdapter(protected val jdbc: JdbcTemplate) : StockPort {
    override val transactional = true

    /** 롤백이 처리하므로 할 일이 없다. */
    override fun restoreAfterFailedOrder(productId: Long, userId: String) = Unit

    /** 되돌린 주문 기록에 처음 들어갈 때만 수량을 늘린다. 호출한 쪽의 트랜잭션에 함께 묶인다. */
    override fun restoreAfterCancel(productId: Long, orderId: Long) {
        val first = jdbc.update("INSERT IGNORE INTO stock_restore (order_id) VALUES (?)", orderId) == 1
        if (first) jdbc.update("UPDATE stock SET quantity = quantity + 1 WHERE product_id = ?", productId)
    }

    override fun reset(productId: Long, quantity: Int) {
        jdbc.update(
            "INSERT INTO stock (product_id, quantity, version) VALUES (?, ?, 0) " +
                "ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), version = 0",
            productId, quantity,
        )
        jdbc.update("DELETE FROM stock_restore")
    }

    override fun remaining(productId: Long): Int =
        jdbc.queryForObject("SELECT quantity FROM stock WHERE product_id = ?", Int::class.java, productId) ?: 0
}

/** ① 락 없음 (기준선). 읽은 값으로 덮어써서 동시 요청이 겹치면 갱신이 유실되고 초과 판매가 난다. */
@Component
class NoLockStockAdapter(jdbc: JdbcTemplate) : JdbcStockAdapter(jdbc) {
    override val mode = DeductMode.NO_LOCK

    override fun deduct(productId: Long, userId: String): DeductResult {
        val quantity = remaining(productId)
        if (quantity <= 0) return DeductResult.SOLD_OUT
        jdbc.update("UPDATE stock SET quantity = ? WHERE product_id = ?", quantity - 1, productId)
        return DeductResult.SUCCESS
    }
}

/** ② 조건부 UPDATE. 확인과 차감을 쿼리 하나로 하고, 영향받은 행 수로 성공을 판단한다. */
@Component
class ConditionalUpdateStockAdapter(jdbc: JdbcTemplate) : JdbcStockAdapter(jdbc) {
    override val mode = DeductMode.CONDITIONAL_UPDATE

    override fun deduct(productId: Long, userId: String): DeductResult {
        val updated = jdbc.update("UPDATE stock SET quantity = quantity - 1 WHERE product_id = ? AND quantity > 0", productId)
        return if (updated == 1) DeductResult.SUCCESS else DeductResult.SOLD_OUT
    }
}

/** ③ 비관적 락. 행을 잠그고 커밋까지 유지한다. 트랜잭션 밖에서는 동작하지 않는다. */
@Component
class PessimisticLockStockAdapter(jdbc: JdbcTemplate) : JdbcStockAdapter(jdbc) {
    override val mode = DeductMode.PESSIMISTIC_LOCK

    override fun deduct(productId: Long, userId: String): DeductResult {
        val quantity = jdbc.queryForObject(
            "SELECT quantity FROM stock WHERE product_id = ? FOR UPDATE", Int::class.java, productId,
        ) ?: 0
        if (quantity <= 0) return DeductResult.SOLD_OUT
        jdbc.update("UPDATE stock SET quantity = quantity - 1 WHERE product_id = ?", productId)
        return DeductResult.SUCCESS
    }
}

/** ④ 낙관적 락. 버전이 바뀌었으면 충돌로 보고, 주문 쪽이 새 트랜잭션으로 다시 시도한다. */
@Component
class OptimisticLockStockAdapter(jdbc: JdbcTemplate) : JdbcStockAdapter(jdbc) {
    override val mode = DeductMode.OPTIMISTIC_LOCK

    override fun deduct(productId: Long, userId: String): DeductResult {
        val (quantity, version) = jdbc.queryForObject(
            "SELECT quantity, version FROM stock WHERE product_id = ?",
            { rs, _ -> rs.getInt("quantity") to rs.getLong("version") },
            productId,
        )!!
        if (quantity <= 0) return DeductResult.SOLD_OUT
        val updated = jdbc.update(
            "UPDATE stock SET quantity = quantity - 1, version = version + 1 WHERE product_id = ? AND version = ?",
            productId, version,
        )
        if (updated == 0) throw StockConflict()
        return DeductResult.SUCCESS
    }
}
