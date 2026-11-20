package com.ticketsales.queue

import com.ticketsales.common.contract.OrderStatsContract
import com.ticketsales.common.contract.StockQueryContract
import org.springframework.stereotype.Service

enum class SalesStatus {
    /** 지금 < 오픈 시각. 줄 서기도 받지 않는다. */
    BEFORE_OPEN,
    /** 재고 > 0 */
    ON_SALE,
    /** 재고 = 0, 아직 완료되지 않은 주문이 있다. 취소표가 나올 수 있다. */
    SOLD_OUT,
    /** 재고 = 0, 완료 주문 수 = 전체 재고. 더 돌아올 재고가 없다. */
    FINAL_SOLD_OUT,
}

/**
 * 판매 상태는 저장하지 않고 조건으로 매번 계산한다. 재고가 돌아오면 자동으로 판매 중이 된다.
 * 최종 매진을 "결제 대기 0"으로 판단하면, Redis 방식에서 차감 직후 주문 저장 전의 순간에 잘못 참이 된다.
 * 그래서 늘어나기만 하는 "완료 주문 수"가 전체 재고에 닿았는지로 판단한다.
 */
@Service
class SalesStatusQuery(
    private val stock: StockQueryContract,
    private val orders: OrderStatsContract,
    private val opening: SaleOpening,
) {
    fun current(productId: Long): SalesStatus = when {
        !opening.isOpen() -> SalesStatus.BEFORE_OPEN
        stock.remaining(productId) > 0 -> SalesStatus.ON_SALE
        orders.completedCount(productId) < stock.total(productId) -> SalesStatus.SOLD_OUT
        else -> SalesStatus.FINAL_SOLD_OUT
    }
}
