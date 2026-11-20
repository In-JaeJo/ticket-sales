package com.ticketsales.common.event

/** 도메인 사이를 오가는 이벤트의 공통 표식. 이벤트 클래스는 이 패키지에 둔다. */
interface DomainEvent {
    val orderId: Long
}

data class OrderCreated(override val orderId: Long, val userId: String, val productId: Long, val amount: Long) : DomainEvent
data class PaymentCompleted(override val orderId: Long, val approvalId: String) : DomainEvent
data class PaymentFailed(override val orderId: Long, val reason: String) : DomainEvent
data class OrderCancelled(override val orderId: Long, val userId: String, val productId: Long, val reason: String) : DomainEvent

object Topics {
    const val ORDER_CREATED = "order.created"
    const val PAYMENT_COMPLETED = "payment.completed"
    const val PAYMENT_FAILED = "payment.failed"
    const val ORDER_CANCELLED = "order.cancelled"
    val ALL = listOf(ORDER_CREATED, PAYMENT_COMPLETED, PAYMENT_FAILED, ORDER_CANCELLED)
}
