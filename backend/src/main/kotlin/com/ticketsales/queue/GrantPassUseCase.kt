package com.ticketsales.queue

import com.ticketsales.queue.port.PassStorePort
import org.springframework.stereotype.Service
import java.time.Duration

/** 입장 토큰 발급. 대기열 스케줄러(3단계)와 방식 비교 측정용 일괄 발급이 함께 쓴다. */
@Service
class GrantPassUseCase(private val passes: PassStorePort) {
    fun grantAll(userIds: Collection<String>): Int {
        userIds.forEach { passes.grant(it, PASS_TTL) }
        return userIds.size
    }

    companion object {
        val PASS_TTL: Duration = Duration.ofSeconds(180)
    }
}
