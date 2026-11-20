package com.ticketsales.queue

import com.ticketsales.queue.port.PassStorePort
import com.ticketsales.queue.port.QueueStorePort
import org.springframework.stereotype.Service

/**
 * 입장. 판매 중일 때만 실제 대기자 n명을 꺼내 토큰을 주고, 그다음 커서를 올린다.
 * 토큰을 먼저 저장해야 커서를 보고 바로 주문한 사람이 403을 받지 않는다.
 */
@Service
class AdmitUseCase(
    private val queue: QueueStorePort,
    private val passes: PassStorePort,
    private val status: SalesStatusQuery,
) {
    fun admit(n: Int, productId: Long): Int {
        if (n <= 0 || status.current(productId) != SalesStatus.ON_SALE) return 0
        val admitted = queue.pop(n)
        admitted.forEach { passes.grant(it.userId, GrantPassUseCase.PASS_TTL) }
        admitted.maxOfOrNull { it.ticket }?.let { queue.advanceCursor(it) }
        return admitted.size
    }
}
