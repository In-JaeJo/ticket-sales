package com.ticketsales.product.port

import com.ticketsales.common.contract.DeductResult
import com.ticketsales.product.DeductMode

/** 재고 차감 방식 하나. 5가지 방식이 이 인터페이스를 구현한다. */
interface StockPort {
    val mode: DeductMode
    val transactional: Boolean
    fun deduct(productId: Long, userId: String): DeductResult
    /** 주문 저장 실패: 재고 +1, 구매 이력 삭제 */
    fun restoreAfterFailedOrder(productId: Long, userId: String)
    /**
     * 주문 취소: 재고 +1만 (재구매 불가라 구매 이력은 유지).
     * 같은 주문으로 다시 불려도 한 번만 늘린다. 처리 직후 앱이 죽으면 같은 취소 메시지가 다시 처리되기 때문이다.
     */
    fun restoreAfterCancel(productId: Long, orderId: Long)
    fun reset(productId: Long, quantity: Int)
    /** 취소로 재고를 되돌린 주문 수. 취소는 커밋됐지만 아직 복구 전인 주문을 정합성 계산에서 구분하려고 쓴다. */
    fun restoredCount(productId: Long): Long
    fun remaining(productId: Long): Int
}

/** 지금 고른 방식을 돌려준다. */
interface StockPortSelector {
    fun current(): StockPort
}
