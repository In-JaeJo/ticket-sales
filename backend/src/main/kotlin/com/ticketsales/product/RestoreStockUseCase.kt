package com.ticketsales.product

import com.ticketsales.common.event.OrderCancelled
import com.ticketsales.product.port.StockPortSelector
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 보상: 주문이 취소되면 재고를 되돌린다. 재고를 가진 product가 직접 한다.
 * DB 방식은 "되돌린 주문 기록"과 수량 +1을 한 트랜잭션으로 묶는다.
 */
@Service
class RestoreStockUseCase(private val stocks: StockPortSelector) {
    @Transactional
    fun restore(event: OrderCancelled) = stocks.current().restoreAfterCancel(event.productId, event.orderId)
}
