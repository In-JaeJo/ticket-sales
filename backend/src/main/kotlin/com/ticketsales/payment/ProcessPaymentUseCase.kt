package com.ticketsales.payment

import com.ticketsales.common.event.OrderCreated
import com.ticketsales.common.event.PaymentCompleted
import com.ticketsales.common.event.PaymentFailed
import com.ticketsales.common.event.Topics
import com.ticketsales.payment.port.PaymentEventPort
import com.ticketsales.payment.port.PaymentPort
import com.ticketsales.payment.port.PaymentResult
import org.springframework.stereotype.Service

/**
 * 결제 결과는 받은 이벤트의 판 번호 그대로 Outbox에 기록한다. 처리 중에 리셋돼도 이전 판 결과가 새 판에 섞이지 않는다.
 * PG 호출은 트랜잭션 밖에서 한다. PG가 느려도 DB 커넥션을 붙잡지 않는다. Outbox 기록은 한 건이라 저장소의 트랜잭션으로 충분하다.
 */
@Service
class ProcessPaymentUseCase(
    private val pg: PaymentPort,
    private val events: PaymentEventPort,
) {
    fun process(event: OrderCreated, runId: Long) {
        when (val result = pg.pay(event.orderId, event.amount)) {
            is PaymentResult.Approved ->
                events.publish(Topics.PAYMENT_COMPLETED, runId, PaymentCompleted(event.orderId, result.approvalId))
            is PaymentResult.Declined ->
                events.publish(Topics.PAYMENT_FAILED, runId, PaymentFailed(event.orderId, result.reason))
        }
    }
}
