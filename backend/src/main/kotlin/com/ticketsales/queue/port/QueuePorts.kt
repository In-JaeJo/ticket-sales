package com.ticketsales.queue.port

import java.time.Duration
import java.time.Instant

data class Waiting(val userId: String, val ticket: Long)

interface QueueStorePort {
    /** 번호표 발급. 이미 줄 서 있으면 기존 번호표를 돌려준다. */
    fun enter(userId: String): Long
    fun cursor(): Long
    /** 실제로 기다리는 사람을 번호표 순으로 n명 꺼낸다. 이탈자는 이미 빠져 있다. */
    fun pop(n: Int): List<Waiting>
    fun leave(userId: String): Boolean
    /** 커서는 앞으로만 간다. */
    fun advanceCursor(to: Long)
    fun waitingCount(): Long
}

interface PassStorePort {
    fun grant(userId: String, ttl: Duration)
    /** 꺼내면서 지운다. 있었으면 true. */
    fun consume(userId: String): Boolean
    fun has(userId: String): Boolean
    fun revoke(userId: String)
}

interface RateLimitPort {
    fun tryAcquire(userId: String): Boolean
}

/** 오픈 시각. 없으면 이미 오픈한 것이다. */
interface SaleSchedulePort {
    fun openAt(): Instant?
    fun setOpenAt(at: Instant)
}
