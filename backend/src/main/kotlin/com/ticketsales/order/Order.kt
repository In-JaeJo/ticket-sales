package com.ticketsales.order

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class OrderStatus { PENDING, COMPLETED, CANCELLED }

@Entity
@Table(name = "orders")
class Order(
    @Column(name = "user_id", nullable = false)
    val userId: String,
    @Column(name = "product_id", nullable = false)
    val productId: Long,
    @Column(nullable = false)
    val amount: Long,
    /** 주문이 만들어진 판 번호. 이 주문의 모든 이벤트가 지금 판이 아니라 이 값을 싣는다. */
    @Column(name = "run_id", nullable = false)
    val runId: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderStatus = OrderStatus.PENDING
        protected set

    /** PENDING일 때만 완료. 이미 취소된 주문이면 false (결제 후 취소). */
    fun complete(): Boolean {
        if (status != OrderStatus.PENDING) return false
        status = OrderStatus.COMPLETED
        return true
    }

    /** PENDING일 때만 취소. 실제로 바뀌었을 때만 true. 이 값으로 취소 이벤트를 낼지 정한다. */
    fun cancel(): Boolean {
        if (status != OrderStatus.PENDING) return false
        status = OrderStatus.CANCELLED
        return true
    }
}

enum class RejectReason { NO_PASS, SOLD_OUT, DUPLICATE, BUSY }

class OrderRejected(val reason: RejectReason) : RuntimeException(reason.name)
