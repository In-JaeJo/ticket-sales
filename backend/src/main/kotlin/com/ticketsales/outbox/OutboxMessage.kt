package com.ticketsales.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "outbox")
class OutboxMessage(
    @Column(name = "message_id", nullable = false, unique = true)
    val messageId: String,
    @Column(name = "run_id", nullable = false)
    val runId: Long,
    @Column(nullable = false)
    val topic: String,
    @Column(name = "message_key", nullable = false)
    val messageKey: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    val payload: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Column(name = "published_at")
    var publishedAt: Instant? = null
        protected set

    fun markPublished(at: Instant) {
        publishedAt = at
    }
}
