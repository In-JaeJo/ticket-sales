package com.ticketsales.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface OutboxRepository : JpaRepository<OutboxMessage, Long> {
    fun findTop500ByPublishedAtIsNullOrderByIdAsc(): List<OutboxMessage>
    fun findTopByPublishedAtIsNotNullOrderByIdDesc(): OutboxMessage?
    fun countByPublishedAtIsNull(): Long

    /** 발행한 행들의 발행 시각을 UPDATE 한 번으로 기록한다 */
    @Transactional
    @Modifying
    @Query("UPDATE OutboxMessage m SET m.publishedAt = :at WHERE m.id IN :ids")
    fun markPublished(@Param("ids") ids: Collection<Long>, @Param("at") at: Instant): Int
}
