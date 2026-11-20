package com.ticketsales.queue

import com.ticketsales.queue.port.SaleSchedulePort
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 정각 오픈. 시계 기준은 서버이고, 화면에는 남은 시간을 보내 손님 PC의 시계와 무관하게 한다. */
@Service
class SaleOpening(
    private val schedule: SaleSchedulePort,
    private val clock: Clock,
) {
    /** 오픈까지 남은 시간. 이미 오픈했으면 0 */
    fun openIn(): Duration {
        val at = schedule.openAt() ?: return Duration.ZERO
        val left = Duration.between(clock.instant(), at)
        return if (left.isNegative) Duration.ZERO else left
    }

    fun isOpen(): Boolean = openIn().isZero

    /** 지금부터 delay 뒤에 오픈한다. 0이면 기록하지 않아 바로 오픈 상태다. 오픈 시각을 돌려준다. */
    fun scheduleAfter(delay: Duration): Instant {
        val at = clock.instant().plus(delay)
        if (delay > Duration.ZERO) schedule.setOpenAt(at)
        return at
    }
}
