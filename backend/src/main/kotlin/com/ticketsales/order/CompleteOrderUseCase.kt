package com.ticketsales.order

import com.ticketsales.order.port.OrderMetricsPort
import com.ticketsales.order.port.OrderStorePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CompleteOrderUseCase(
    private val orders: OrderStorePort,
    private val metrics: OrderMetricsPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이미 취소된 주문에 결제 성공이 늦게 오면 주문은 CANCELLED로 두고 "결제 후 취소"로 집계한다.
     * 취소와 같은 이유로 행을 잠그고 읽는다. 잠그지 않으면 취소 이벤트가 나간 주문이 COMPLETED로 덮어써질 수 있다.
     */
    @Transactional
    fun complete(orderId: Long) {
        val order = orders.findByIdForUpdate(orderId) ?: error("주문 없음: $orderId")
        if (!order.complete() && order.status == OrderStatus.CANCELLED) {
            log.warn("결제 후 취소: 주문 {}", orderId)
            metrics.paidAfterCancel()
        }
    }
}
