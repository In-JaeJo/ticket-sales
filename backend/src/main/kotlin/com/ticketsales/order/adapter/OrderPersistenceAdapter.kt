package com.ticketsales.order.adapter

import com.ticketsales.common.contract.OrderStatsContract
import com.ticketsales.order.Order
import com.ticketsales.order.OrderStatus
import com.ticketsales.order.port.OrderStorePort
import jakarta.persistence.LockModeType
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import java.time.Instant

interface OrderJpaRepository : JpaRepository<Order, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    fun findForUpdate(id: Long): Order?

    @Query("select o.id from Order o where o.status = :status and o.createdAt < :before order by o.id")
    fun findIds(status: OrderStatus, before: Instant, pageable: Pageable): List<Long>

    fun countByProductIdAndStatus(productId: Long, status: OrderStatus): Long

    fun existsByUserIdAndProductId(userId: String, productId: Long): Boolean
}

@Component
class OrderPersistenceAdapter(private val repository: OrderJpaRepository) : OrderStorePort, OrderStatsContract {
    override fun save(order: Order): Order = repository.saveAndFlush(order)
    override fun findById(orderId: Long): Order? = repository.findById(orderId).orElse(null)
    override fun findByIdForUpdate(orderId: Long): Order? = repository.findForUpdate(orderId)
    override fun findPendingIdsCreatedBefore(before: Instant, limit: Int): List<Long> =
        repository.findIds(OrderStatus.PENDING, before, PageRequest.of(0, limit))

    override fun completedCount(productId: Long): Long = repository.countByProductIdAndStatus(productId, OrderStatus.COMPLETED)
    override fun hasOrdered(userId: String, productId: Long): Boolean = repository.existsByUserIdAndProductId(userId, productId)
}
