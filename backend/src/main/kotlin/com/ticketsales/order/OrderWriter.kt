package com.ticketsales.order

import com.ticketsales.common.contract.StockDeductionContract
import com.ticketsales.common.event.OrderCreated
import com.ticketsales.common.event.Topics
import com.ticketsales.order.port.OrderEventPort
import com.ticketsales.order.port.OrderStorePort
import com.ticketsales.order.port.PricingPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** 트랜잭션 구간. 별도 빈이라 프록시를 거쳐 트랜잭션이 실제로 걸린다. */
@Service
class OrderWriter(
    private val stock: StockDeductionContract,
    private val orders: OrderStorePort,
    private val events: OrderEventPort,
    private val pricing: PricingPort,
    private val clock: Clock,
) {
    @Transactional
    fun place(userId: String, productId: Long, runId: Long, deductInside: Boolean): Long {
        if (deductInside) requireDeducted(stock.deduct(productId, userId))
        val order = orders.save(Order(userId, productId, pricing.price(productId), runId, clock.instant()))
        events.publish(Topics.ORDER_CREATED, runId, OrderCreated(order.id, userId, productId, order.amount))
        return order.id
    }
}
