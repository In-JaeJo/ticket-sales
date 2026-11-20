package com.ticketsales.queue.adapter

import com.ticketsales.queue.AdmitUseCase
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 매 초 입장률만큼 입장시킨다 (Backpressure). 입장률은 관제 화면에서 바꾼다. */
@Component
class QueueAdmissionScheduler(
    private val admit: AdmitUseCase,
    @Value("\${ticket.product-id}") private val productId: Long,
    @Value("\${ticket.queue.admission-rate}") admissionRate: Int,
    registry: MeterRegistry,
) {
    private val admitted = registry.counter("ticket.queue.admitted")

    @Volatile
    var admissionRate: Int = admissionRate

    @Scheduled(fixedRate = 1000)
    fun run() {
        admitted.increment(admit.admit(admissionRate, productId).toDouble())
    }
}
