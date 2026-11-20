package com.ticketsales.payment.port

import com.ticketsales.common.event.DomainEvent

sealed interface PaymentResult {
    data class Approved(val approvalId: String) : PaymentResult
    data class Declined(val reason: String) : PaymentResult
}

interface PaymentPort {
    /** 주문 ID가 멱등 키다. 같은 주문으로 다시 불러도 한 번만 청구된다. 청구 뒤 실패해 재시도돼도 두 번 청구되지 않게. */
    fun pay(orderId: Long, amount: Long): PaymentResult
}

interface PaymentEventPort {
    fun publish(topic: String, runId: Long, event: DomainEvent)
}
