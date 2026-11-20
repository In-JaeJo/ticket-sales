package com.ticketsales.order

import com.ticketsales.common.contract.CurrentRunContract
import com.ticketsales.common.contract.DeductResult
import com.ticketsales.common.contract.QueuePassContract
import com.ticketsales.common.contract.StockConflict
import com.ticketsales.common.contract.StockDeductionContract
import com.ticketsales.order.port.OrderMetricsPort
import org.springframework.stereotype.Service

/**
 * 주문 접수. 트랜잭션을 직접 열지 않는다.
 * - Redis 방식: 트랜잭션 밖에서 차감하고(Redis는 DB 롤백으로 되돌아가지 않는다), 저장이 실패하면 이번에 차감한 것만 되돌린다.
 * - DB 방식: 차감과 저장을 한 트랜잭션에서 한다. 낙관적 락 충돌은 새 트랜잭션으로 다시 시도한다(같은 트랜잭션에서 다시 하면 같은 충돌이 반복된다).
 */
@Service
class PlaceOrderUseCase(
    private val passes: QueuePassContract,
    private val stock: StockDeductionContract,
    private val runs: CurrentRunContract,
    private val writer: OrderWriter,
    private val metrics: OrderMetricsPort,
) {
    fun place(userId: String, productId: Long): Long {
        if (!passes.consume(userId)) throw OrderRejected(RejectReason.NO_PASS)
        return placeAfterPass(userId, productId)
    }

    /** 입장 토큰 단계 다음. 동시성 테스트는 재고 단계를 시험하려고 이 메서드를 직접 부른다. */
    fun placeAfterPass(userId: String, productId: Long): Long {
        val runId = runs.current()
        if (stock.transactional) {
            return retryOnConflict { writer.place(userId, productId, runId, deductInside = true) }
        }

        requireDeducted(stock.deduct(productId, userId))
        try {
            return writer.place(userId, productId, runId, deductInside = false)
        } catch (e: Exception) {
            stock.restoreAfterFailedOrder(productId, userId)
            throw e
        }
    }

    private fun retryOnConflict(block: () -> Long): Long {
        repeat(MAX_OPTIMISTIC_RETRIES) {
            try {
                return block()
            } catch (e: StockConflict) {
                // 롤백된 트랜잭션은 버리고 새 트랜잭션으로 다시 시도한다
                metrics.optimisticRetry()
            }
        }
        throw OrderRejected(RejectReason.BUSY)
    }

    companion object {
        const val MAX_OPTIMISTIC_RETRIES = 10
    }
}

internal fun requireDeducted(result: DeductResult) = when (result) {
    DeductResult.SUCCESS -> Unit
    DeductResult.SOLD_OUT -> throw OrderRejected(RejectReason.SOLD_OUT)
    DeductResult.DUPLICATE -> throw OrderRejected(RejectReason.DUPLICATE)
}
