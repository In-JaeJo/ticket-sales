package com.ticketsales.demo

import com.ticketsales.common.contract.CurrentRunContract
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 실행기에게 넘기는 투입 요청. id는 투입할 때의 판 번호라 투입마다 다르다.
 * openAt은 오픈 시각(epoch 밀리초). 가상 손님은 이때까지 기다렸다가 arrivalSeconds 안에 몰린다.
 */
data class CrowdRequest(val id: Long, val users: Int, val arrivalSeconds: Int, val openAt: Long)

enum class CrowdState { IDLE, REQUESTED, RUNNING, DONE }

/**
 * 가상 손님 투입 요청과 실행기 상태. 앱은 Redis에 요청만 적고, 실행기 컨테이너가 1초마다 확인해 k6를 실행·중지한다.
 * 앱이 Docker를 제어하지 않기 위한 구조다. 리셋은 Redis 키를 모두 지우므로 요청도 사라지고, 실행기는 k6를 멈춘다.
 */
@Component
class CrowdControl(
    private val redis: StringRedisTemplate,
    private val demo: DemoService,
    private val runs: CurrentRunContract,
) {
    /** 설정에 적용된 재고·입장률과 오픈까지 남길 시간으로 리셋하고 투입 요청을 적는다. 이전 투입은 리셋으로 멈춘다. */
    fun start(users: Int, arrivalSeconds: Int, openInSeconds: Int): CrowdRequest {
        require(users in 1..MAX_USERS) { "가상 손님은 1~${MAX_USERS}명이에요." }
        require(arrivalSeconds in 0..MAX_ARRIVAL_SECONDS) { "도착 시간은 0~${MAX_ARRIVAL_SECONDS}초예요." }
        require(openInSeconds in 0..MAX_OPEN_IN_SECONDS) { "오픈까지는 0~${MAX_OPEN_IN_SECONDS}초예요." }
        val openAt = demo.reset(Duration.ofSeconds(openInSeconds.toLong())).toEpochMilli()
        val request = CrowdRequest(runs.current(), users, arrivalSeconds, openAt)
        redis.opsForHash<String, String>().putAll(
            REQUEST,
            mapOf("id" to "${request.id}", "users" to "$users", "arrivalSeconds" to "$arrivalSeconds", "openAt" to "$openAt"),
        )
        return request
    }

    fun stop() {
        redis.delete(REQUEST)
    }

    /** 실행기가 1초마다 부른다. 부를 때마다 실행기가 살아 있다는 표시를 남긴다. */
    fun pollByRunner(): CrowdRequest? {
        redis.opsForValue().set(RUNNER, "1", RUNNER_TTL)
        return request()
    }

    /** 실행기의 상태 보고. 지금 요청이 아닌 보고(이미 멈춘 투입)는 버린다. */
    fun report(id: Long, state: CrowdState) {
        if (request()?.id != id) return
        when (state) {
            CrowdState.RUNNING -> redis.opsForValue().set(RUNNING, "$id")
            CrowdState.DONE -> redis.opsForValue().set(DONE, "$id")
            else -> throw IllegalArgumentException("실행기는 RUNNING·DONE만 보고해요.")
        }
    }

    fun request(): CrowdRequest? {
        val values = redis.opsForHash<String, String>().entries(REQUEST)
        if (values.isEmpty()) return null
        return CrowdRequest(
            values.getValue("id").toLong(), values.getValue("users").toInt(),
            values.getValue("arrivalSeconds").toInt(), values.getValue("openAt").toLong(),
        )
    }

    fun state(): CrowdState {
        val id = request()?.id?.toString() ?: return CrowdState.IDLE
        return when (id) {
            redis.opsForValue().get(DONE) -> CrowdState.DONE
            redis.opsForValue().get(RUNNING) -> CrowdState.RUNNING
            else -> CrowdState.REQUESTED
        }
    }

    fun runnerOnline(): Boolean = redis.hasKey(RUNNER)

    companion object {
        const val MAX_USERS = 10_000
        const val MAX_ARRIVAL_SECONDS = 600
        const val MAX_OPEN_IN_SECONDS = 3600
        const val REQUEST = "crowd:request"
        const val RUNNING = "crowd:running"
        const val DONE = "crowd:done"
        const val RUNNER = "crowd:runner"
        val RUNNER_TTL: Duration = Duration.ofSeconds(3)
    }
}
