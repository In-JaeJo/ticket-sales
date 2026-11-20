package com.ticketsales

import com.ticketsales.common.event.Topics
import com.ticketsales.order.CancelOrderUseCase
import com.ticketsales.order.CompleteOrderUseCase
import com.ticketsales.order.OrderRejected
import com.ticketsales.order.PlaceOrderUseCase
import com.ticketsales.order.RejectReason
import com.ticketsales.product.DeductMode
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 동시성: 재고 30, 사용자 50명이 20번씩 동시에 주문한다(재고 < 사용자 수라야 초과 판매를 잡아낼 수 있다). */
class ConcurrencyTest : IntegrationTest() {

    @Autowired lateinit var placeOrder: PlaceOrderUseCase
    @Autowired lateinit var registry: MeterRegistry
    @Autowired lateinit var cancelOrder: CancelOrderUseCase
    @Autowired lateinit var completeOrder: CompleteOrderUseCase

    private fun optimisticRetries() = registry.find("ticket.stock.optimistic.retry").counters().sumOf { it.count() }

    private val stockForRace = 30
    private val users = 50
    private val attempts = 1000

    /** 모든 스레드가 동시에 출발하도록 맞춘 뒤 실행하고, 결과 종류별 건수를 돌려준다. */
    private fun race(task: (Int) -> Unit): Map<String, Int> {
        val outcomes = ConcurrentHashMap<String, AtomicInteger>()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(users)
        repeat(attempts) { i ->
            pool.submit {
                start.await()
                val outcome = runCatching { task(i) }.fold(
                    { "SUCCESS" },
                    { (it as? OrderRejected)?.reason?.name ?: it.javaClass.simpleName },
                )
                outcomes.computeIfAbsent(outcome) { AtomicInteger() }.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.MINUTES)
        return outcomes.mapValues { it.value.get() }
    }

    private fun distinctBuyers(): Int = jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM orders", Int::class.java)!!

    @TestFactory
    fun `안전한 4가지 방식은 초과 판매와 중복 구매가 없다`() =
        listOf(DeductMode.CONDITIONAL_UPDATE, DeductMode.PESSIMISTIC_LOCK, DeductMode.OPTIMISTIC_LOCK, DeductMode.REDIS_LUA)
            .map { mode ->
                dynamicTest(mode.name) {
                    demo.configure(initialStock = stockForRace, admissionRate = null, poolSize = null)
                    demo.changeMode(mode)

                    val retriesBefore = optimisticRetries()
                    val outcomes = race { i -> placeOrder.placeAfterPass("user-${i % users}", productId) }

                    println("[동시 주문] $mode $outcomes")
                    assertEquals(stockForRace, orderCount(), "주문 수 = 재고")
                    assertEquals(0, stock(), "남은 재고")
                    assertEquals(stockForRace, distinctBuyers(), "1인 1건")
                    if (mode == DeductMode.OPTIMISTIC_LOCK) {
                        // 충돌하면 새 트랜잭션으로 다시 시도한다. 재시도가 실제로 일어났는데도 결과가 맞아야 한다
                        assertTrue(optimisticRetries() > retriesBefore, "낙관적 락 충돌 재시도가 일어나야 한다")
                    }
                }
            }

    @Test
    fun `Redis 방식에서 품절·중복 응답 뒤에 재고와 구매 이력이 변하지 않는다`() {
        demo.configure(initialStock = 1, admissionRate = null, poolSize = null)
        demo.reset()

        placeOrder.placeAfterPass("u1", productId)
        val soldOut = runCatching { placeOrder.placeAfterPass("u2", productId) }.exceptionOrNull() as OrderRejected
        val duplicate = runCatching { placeOrder.placeAfterPass("u1", productId) }.exceptionOrNull() as OrderRejected

        assertEquals(RejectReason.SOLD_OUT, soldOut.reason)
        assertEquals(RejectReason.DUPLICATE, duplicate.reason)
        assertEquals(0, stock())
        assertEquals(setOf("u1"), redis.opsForSet().members("bought:$productId"))
    }

    @Test
    fun `같은 사용자가 토큰 1개로 동시에 10번 주문하면 1건만 재고 단계에 도달한다`() {
        grantPass("u1")
        val outcomes = ConcurrentHashMap<String, AtomicInteger>()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)
        repeat(10) {
            pool.submit {
                start.await()
                val outcome = runCatching { placeOrder.place("u1", productId) }
                    .fold({ "SUCCESS" }, { (it as OrderRejected).reason.name })
                outcomes.computeIfAbsent(outcome) { AtomicInteger() }.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertEquals(1, outcomes["SUCCESS"]?.get())
        assertEquals(9, outcomes["NO_PASS"]?.get())
        assertEquals(1, orderCount())
    }

    /**
     * 결제 대기 주문을 DB에 직접 만든다. 주문 API를 거치면 결제 소비자가 끼어들어 경쟁 대상이 바뀌므로,
     * 경쟁시킬 두 요청만 부르려고 행을 바로 넣는다.
     */
    private fun pendingOrders(count: Int): List<Long> = (1..count).map { i ->
        jdbc.update(
            "INSERT INTO orders (user_id, product_id, status, amount, run_id, created_at) VALUES (?, ?, 'PENDING', 50000, ?, NOW(6))",
            "race-$i", productId, runs.current(),
        )
        jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
    }

    /** 주문마다 두 작업을 동시에 출발시킨다. */
    private fun raceEach(orderIds: List<Long>, first: (Long) -> Unit, second: (Long) -> Unit) {
        val pool = Executors.newFixedThreadPool(2)
        orderIds.forEach { id ->
            val start = CountDownLatch(1)
            val tasks = listOf(first, second).map { task -> pool.submit { start.await(); runCatching { task(id) } } }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        }
        pool.shutdown()
    }

    @Test
    fun `같은 주문에 취소 두 건이 동시에 와도 취소 이벤트는 1건`() {
        relay.paused = true // 취소 이벤트가 소비돼 재고가 바뀌지 않게 Outbox에만 쌓는다
        val ids = pendingOrders(RACE_ORDERS)

        raceEach(ids, { cancelOrder.cancel(it, "결제 대기 시간 초과") }, { cancelOrder.cancel(it, "카드 승인 거절") })

        val doubled = ids.associateWith { outboxCount(Topics.ORDER_CANCELLED, it.toString()) }.filterValues { it != 1 }
        assertEquals(emptyMap(), doubled, "취소 이벤트가 1건이 아닌 주문(주문 ID=건수)")
    }

    @Test
    fun `같은 주문에 취소와 결제 완료가 동시에 와도 상태와 취소 이벤트가 맞다`() {
        relay.paused = true
        val ids = pendingOrders(RACE_ORDERS)

        raceEach(ids, { cancelOrder.cancel(it, "결제 대기 시간 초과") }, { completeOrder.complete(it) })

        // 완료로 끝났으면 취소 이벤트 0건, 취소로 끝났으면 1건. 완료인데 재고가 복구되면 초과 판매다
        val broken = ids.filter { id ->
            val expected = if (orderStatus(id) == "CANCELLED") 1 else 0
            outboxCount(Topics.ORDER_CANCELLED, id.toString()) != expected
        }
        assertEquals(emptyList(), broken, "상태와 취소 이벤트가 어긋난 주문")
    }

    /** 시연용: 락이 없으면 실제로 깨진다. 타이밍에 따라 결과가 달라 CI에서는 제외한다(./gradlew demoTest). */
    @Test
    @Tag("demo")
    fun `락 없음 방식은 초과 판매나 정합성 깨짐이 재현된다`() {
        demo.configure(initialStock = stockForRace, admissionRate = null, poolSize = null)
        demo.changeMode(DeductMode.NO_LOCK)

        val outcomes = race { i -> placeOrder.placeAfterPass("user-${i % users}", productId) }

        val orders = orderCount()
        val left = stock()
        println("[락 없음] $outcomes 주문 $orders, 남은 재고 $left, 주문 + 남은 재고 = ${orders + left} (초기 $stockForRace)")
        assertTrue(orders > stockForRace || orders + left != stockForRace, "락이 없는데도 정합성이 지켜짐")
    }

    companion object {
        /** 경쟁 틈이 짧아 한 번으로는 잘 안 걸린다. 여러 주문에서 반복해 확률을 높인다 */
        const val RACE_ORDERS = 100
    }
}
