package com.ticketsales.payment.adapter

import com.ticketsales.payment.port.PaymentPort
import com.ticketsales.payment.port.PaymentResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * 가짜 PG 1종. 실패율·지연은 관제 화면에서 조절한다.
 * 주문 ID로 결과를 기억해 같은 주문은 한 번만 청구한다.
 */
@Component
class FakePgAdapter(
    @Value("\${ticket.pg.failure-rate}") failureRate: Double,
    @Value("\${ticket.pg.delay-ms}") delayMs: Long,
) : PaymentPort {
    @Volatile var failureRate: Double = failureRate
    @Volatile var delayMs: Long = delayMs

    /** PG 일시 장애. 남은 횟수만큼 응답 대신 예외를 던진다(재시도 대상). */
    val transientErrors = AtomicInteger(0)

    /** 실제로 청구한 횟수 (관제·테스트용) */
    val charges = AtomicInteger(0)

    private val results = ConcurrentHashMap<Long, PaymentResult>()

    override fun pay(orderId: Long, amount: Long): PaymentResult {
        results[orderId]?.let { return it }
        if (transientErrors.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) error("PG 일시 장애")
        if (delayMs > 0) Thread.sleep(delayMs)
        return results.computeIfAbsent(orderId) {
            if (ThreadLocalRandom.current().nextDouble() < failureRate) {
                PaymentResult.Declined("카드 승인 거절")
            } else {
                charges.incrementAndGet()
                PaymentResult.Approved(UUID.randomUUID().toString())
            }
        }
    }

    fun clear() {
        results.clear()
        charges.set(0)
        transientErrors.set(0)
    }
}
