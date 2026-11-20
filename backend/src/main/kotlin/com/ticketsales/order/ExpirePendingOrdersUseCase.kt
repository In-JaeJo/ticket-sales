package com.ticketsales.order

import com.ticketsales.order.port.OrderStorePort
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/** 결제 대기 시간 초과. 결제 메시지가 DLQ로 가서 영영 처리되지 않아도 재고가 돌아오게 한다. */
@Service
class ExpirePendingOrdersUseCase(
    private val orders: OrderStorePort,
    private val cancelOrder: CancelOrderUseCase,
    private val clock: Clock,
) {
    fun expire(timeout: Duration): Int {
        val ids = orders.findPendingIdsCreatedBefore(clock.instant().minus(timeout), BATCH)
        ids.forEach { cancelOrder.cancel(it, "결제 대기 시간 초과") }
        return ids.size
    }

    companion object {
        const val BATCH = 100
    }
}
