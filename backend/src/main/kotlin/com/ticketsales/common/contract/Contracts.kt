package com.ticketsales.common.contract

enum class DeductResult { SUCCESS, DUPLICATE, SOLD_OUT }

/** 주문 → 재고. 구현은 product. */
interface StockDeductionContract {
    /** DB 트랜잭션에 참여하는 방식이면 true (롤백으로 자동 복구). Redis 방식은 false. */
    val transactional: Boolean
    fun deduct(productId: Long, userId: String): DeductResult
    /** 주문 저장이 실패했을 때. 이번 요청에서 차감에 성공한 경우에만 부른다. 품절·중복 응답 뒤에 부르면 재고가 늘어난다. */
    fun restoreAfterFailedOrder(productId: Long, userId: String)
}

/** 낙관적 락 충돌. 주문 쪽이 트랜잭션 바깥에서 새 트랜잭션으로 다시 시도한다. */
class StockConflict : RuntimeException("재고 동시 수정 충돌")

/** 대기열 → 재고 조회. 구현은 product. */
interface StockQueryContract {
    fun remaining(productId: Long): Int
    /** 이번 판의 전체 재고 (리셋할 때 정한 값) */
    fun total(productId: Long): Int
}

/** 대기열 → 주문 조회. 구현은 order. */
interface OrderStatsContract {
    /** 결제까지 끝난 주문 수. 늘어나기만 한다. */
    fun completedCount(productId: Long): Long
    /** 주문 기록이 있으면 true. 취소돼도 재구매는 불가하므로 포함한다. */
    fun hasOrdered(userId: String, productId: Long): Boolean
}

/** 주문 → 대기열 입장 토큰. 구현은 queue. 꺼내면서 지운다(토큰 1개 = 시도 1번). */
interface QueuePassContract {
    fun consume(userId: String): Boolean
}

/** 지금 판 번호. 리셋마다 1 증가한다. */
interface CurrentRunContract {
    fun current(): Long
}
