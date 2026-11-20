package com.ticketsales.queue

import com.ticketsales.common.contract.OrderStatsContract
import com.ticketsales.queue.port.PassStorePort
import com.ticketsales.queue.port.QueueStorePort
import com.ticketsales.queue.port.RateLimitPort
import org.springframework.stereotype.Service

enum class QueueRejectReason { RATE_LIMITED, NOT_OPEN, ALREADY_ORDERED, SALE_ENDED }

class QueueRejected(val reason: QueueRejectReason) : RuntimeException(reason.name)

data class EnterResult(val ticket: Long?, val position: Long, val admitted: Boolean)

/** 줄 서기. 케이스별 동작은 ARCHITECTURE.md 3.1의 줄 서기 표를 따른다. */
@Service
class EnterQueueUseCase(
    private val queue: QueueStorePort,
    private val passes: PassStorePort,
    private val rateLimit: RateLimitPort,
    private val orders: OrderStatsContract,
    private val status: SalesStatusQuery,
) {
    fun enter(userId: String, productId: Long): EnterResult {
        if (!rateLimit.tryAcquire(userId)) throw QueueRejected(QueueRejectReason.RATE_LIMITED)
        val current = status.current(productId)
        if (current == SalesStatus.BEFORE_OPEN) throw QueueRejected(QueueRejectReason.NOT_OPEN)
        if (passes.has(userId)) return EnterResult(ticket = null, position = 0, admitted = true)
        if (orders.hasOrdered(userId, productId)) throw QueueRejected(QueueRejectReason.ALREADY_ORDERED)
        if (current == SalesStatus.FINAL_SOLD_OUT) throw QueueRejected(QueueRejectReason.SALE_ENDED)

        val ticket = queue.enter(userId)
        return EnterResult(ticket, position = (ticket - queue.cursor()).coerceAtLeast(0), admitted = false)
    }
}

@Service
class LeaveQueueUseCase(
    private val queue: QueueStorePort,
    private val passes: PassStorePort,
) {
    /** 대기 중이면 줄에서 빠지고, 입장 토큰이 있으면 토큰도 지운다. */
    fun leave(userId: String) {
        queue.leave(userId)
        passes.revoke(userId)
    }
}
