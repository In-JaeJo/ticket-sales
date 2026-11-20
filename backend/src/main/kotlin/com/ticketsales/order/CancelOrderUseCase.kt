package com.ticketsales.order

import com.ticketsales.common.event.OrderCancelled
import com.ticketsales.common.event.Topics
import com.ticketsales.order.port.OrderEventPort
import com.ticketsales.order.port.OrderStorePort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 주문 취소. 상태가 실제로 바뀔 때만, 주문의 판 번호로 order.cancelled를 낸다.
 * 주문 행을 잠그고 읽는다. 잠그지 않으면 시간 초과 취소와 결제 실패가 동시에 둘 다 PENDING을 읽고
 * 취소 이벤트를 두 번 내서 재고가 두 번 복구된다.
 */
@Service
class CancelOrderUseCase(
    private val orders: OrderStorePort,
    private val events: OrderEventPort,
) {
    @Transactional
    fun cancel(orderId: Long, reason: String) {
        val order = orders.findByIdForUpdate(orderId) ?: error("주문 없음: $orderId")
        if (!order.cancel()) return
        events.publish(Topics.ORDER_CANCELLED, order.runId, OrderCancelled(order.id, order.userId, order.productId, reason))
    }
}
