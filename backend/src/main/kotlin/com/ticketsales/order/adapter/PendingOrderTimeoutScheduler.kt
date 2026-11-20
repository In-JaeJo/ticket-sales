package com.ticketsales.order.adapter

import com.ticketsales.order.ExpirePendingOrdersUseCase
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class PendingOrderTimeoutScheduler(
    private val useCase: ExpirePendingOrdersUseCase,
    @Value("\${ticket.order.pending-timeout-seconds}") timeoutSeconds: Long,
) {
    @Volatile
    var timeout: Duration = Duration.ofSeconds(timeoutSeconds)

    @Scheduled(fixedDelay = 1000)
    fun run() {
        useCase.expire(timeout)
    }
}
