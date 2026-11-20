package com.ticketsales

import com.ticketsales.common.event.OrderCreated
import com.ticketsales.common.event.Topics
import com.ticketsales.payment.ProcessPaymentUseCase
import com.ticketsales.product.DeductMode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2단계 대표 흐름: 주문 → Outbox → Kafka → 결제 → 완료/취소 → 재고 복구 (Redis 방식) */
class RepresentativeFlowTest : IntegrationTest() {

    @Autowired lateinit var processPayment: ProcessPaymentUseCase

    private fun cancelledPayload(orderId: Long, user: String) =
        """{"orderId":$orderId,"userId":"$user","productId":$productId,"reason":"test"}"""

    @Test
    fun `결제 성공 - 주문 COMPLETED, 재고 1 감소`() {
        grantPass("u1")
        val (status, id) = placeOrder("u1")
        assertEquals(200, status)
        eventually { assertEquals("COMPLETED", orderStatus(id!!)) }
        assertEquals(initialStock - 1, stock())
    }

    @Test
    fun `결제 실패 - 주문 CANCELLED, 재고 복구`() {
        pg.failureRate = 1.0
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        eventually {
            assertEquals("CANCELLED", orderStatus(id!!))
            assertEquals(initialStock, stock())
        }
    }

    @Test
    fun `같은 메시지를 2번 받으면 1번만 처리`() {
        val payload = cancelledPayload(999, "x")
        produce(Topics.ORDER_CANCELLED, "999", payload, messageId = "dup-1")
        produce(Topics.ORDER_CANCELLED, "999", payload, messageId = "dup-1")
        eventually { assertEquals(initialStock + 1, stock()) }
        Thread.sleep(2000)
        assertEquals(initialStock + 1, stock())
    }

    @Test
    fun `처리가 1번 실패한 뒤 재시도로 성공, DLT 없음`() {
        pg.transientErrors.set(1)
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        eventually { assertEquals("COMPLETED", orderStatus(id!!)) }
        assertEquals(1, pg.charges.get())
        assertEquals(0, countRecords("${Topics.ORDER_CREATED}.DLT", id.toString()))
    }

    @Test
    fun `처리 도중 앱이 죽어 남은 처리 중 표시는 재시작 뒤 다시 처리한다`() {
        // 이전 프로세스가 멱등 키를 잡고 처리하다 죽었다: 키는 "처리 중"으로 남고 처리는 끝나지 않았다
        redis.opsForValue().set("processed:crashed-1", "processing:dead-instance", Duration.ofHours(24))
        produce(Topics.ORDER_CANCELLED, "997", cancelledPayload(997, "z"), messageId = "crashed-1")
        eventually { assertEquals(initialStock + 1, stock()) }

        // 다시 처리해 끝났으면 완료로 바뀌어, 같은 메시지가 또 와도 건너뛴다
        produce(Topics.ORDER_CANCELLED, "997", cancelledPayload(997, "z"), messageId = "crashed-1")
        Thread.sleep(2000)
        assertEquals(initialStock + 1, stock())
    }

    @TestFactory
    fun `재고를 되돌린 뒤 완료 표시 전에 앱이 죽어도 재고는 1번만 복구된다`() =
        listOf(DeductMode.REDIS_LUA, DeductMode.CONDITIONAL_UPDATE).map { mode ->
            dynamicTest(mode.name) {
                demo.changeMode(mode)
                produce(Topics.ORDER_CANCELLED, "996", cancelledPayload(996, "w"), messageId = "restored-1")
                eventually { assertEquals(initialStock + 1, stock()) }

                // 재고는 이미 되돌렸는데 멱등 키를 "완료"로 바꾸기 전에 죽었다: 키가 "처리 중"으로 남는다
                redis.opsForValue().set("processed:restored-1", "processing:dead-instance", Duration.ofHours(24))
                produce(Topics.ORDER_CANCELLED, "996", cancelledPayload(996, "w"), messageId = "restored-1")

                // 재시작한 앱이 이어받아 다시 처리해도 같은 주문의 재고를 또 늘리면 안 된다
                eventually { assertEquals("done", redis.opsForValue().get("processed:restored-1")) }
                assertEquals(initialStock + 1, stock())
            }
        }

    @Test
    fun `계속 실패하면 3번 재시도 후 DLT`() {
        pg.transientErrors.set(100)
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        eventually { assertEquals(1, countRecords("${Topics.ORDER_CREATED}.DLT", id.toString())) }
        assertEquals("PENDING", orderStatus(id!!))
    }

    @Test
    fun `시간 초과 취소와 결제 실패가 둘 다 도착해도 재고는 1번만 복구`() {
        // 결제가 4초 걸리다 실패한다. 그사이 시간 초과(1초)가 먼저 주문을 취소한다
        pg.delayMs = 4000
        pg.failureRate = 1.0
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        timeoutScheduler.timeout = Duration.ofSeconds(1)
        eventually(10) { assertEquals("CANCELLED", orderStatus(id!!)) }
        assertEquals(1, outboxCount(Topics.ORDER_CANCELLED, id.toString()))
        assertTrue(cancelReason(id!!).contains("시간 초과"), "먼저 온 취소는 시간 초과")

        // 뒤이어 결제 실패가 도착한다. 이미 CANCELLED라 취소 이벤트를 다시 내지 않는다
        eventually(20) { assertEquals(1, countRecords(Topics.PAYMENT_FAILED, id.toString())) }
        Thread.sleep(3000)
        assertEquals(initialStock, stock())
        assertEquals(1, outboxCount(Topics.ORDER_CANCELLED, id.toString()))
    }

    private fun cancelReason(orderId: Long): String = jdbc.queryForObject(
        "SELECT payload FROM outbox WHERE topic = ? AND message_key = ?", String::class.java, Topics.ORDER_CANCELLED, orderId.toString(),
    )!!

    @Test
    fun `PG 승인 후 Outbox 기록이 1번 실패해도 청구는 1번`() {
        faults.paymentEventFailures.set(1)
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        eventually { assertEquals("COMPLETED", orderStatus(id!!)) }
        assertEquals(1, pg.charges.get())
    }

    @Test
    fun `이전 판 메시지는 처리하지 않는다`() {
        produce(Topics.ORDER_CANCELLED, "998", cancelledPayload(998, "y"), runId = runs.current() - 1)
        Thread.sleep(3000)
        assertEquals(initialStock, stock())
    }

    @Test
    fun `결제 결과 이벤트는 받은 판 번호를 그대로 싣는다`() {
        processPayment.process(OrderCreated(777, "z", productId, 50_000), runId = 4242)
        val run = jdbc.queryForObject(
            "SELECT run_id FROM outbox WHERE topic = ? AND message_key = '777'", Long::class.java, Topics.PAYMENT_COMPLETED,
        )
        assertEquals(4242L, run)
    }

    @Test
    fun `리셋 도중 시간 초과로 취소된 주문의 이벤트도 주문의 판 번호를 싣고 걸러진다`() {
        relay.paused = true // 이벤트를 붙잡아 둔다: 결제는 진행되지 않고 주문은 결제 대기로 남는다
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        val orderRun = runs.current()

        // 리셋 도중: 판 번호는 이미 올라갔고, 시간 초과 스케줄러가 아직 남은 주문을 취소한다
        runs.next()
        timeoutScheduler.timeout = Duration.ofSeconds(1)
        eventually { assertEquals("CANCELLED", orderStatus(id!!)) }
        val run = jdbc.queryForObject(
            "SELECT run_id FROM outbox WHERE topic = ? AND message_key = ?", Long::class.java, Topics.ORDER_CANCELLED, id.toString(),
        )
        assertEquals(orderRun, run, "지금 판이 아니라 주문의 판 번호")

        relay.paused = false
        eventually { assertEquals(0, unpublished()) }
        Thread.sleep(3000)
        assertEquals(initialStock - 1, stock(), "새 판의 재고는 이전 판의 취소로 늘지 않는다")
        assertEquals(0, countRecords("${Topics.ORDER_CANCELLED}.DLT", id.toString()))
    }

    @Test
    fun `트랜잭션 구간이 실패하면 주문은 롤백되고 Redis 재고와 구매 이력이 복구된다`() {
        faults.orderEventFailures.set(1)
        grantPass("u1")
        val (status, _) = runCatching { placeOrder("u1") }.getOrElse { 500 to null }
        assertEquals(500, status)
        assertEquals(0, orderCount())
        assertEquals(initialStock, stock())
        assertFalse(redis.opsForSet().isMember("bought:$productId", "u1") == true)
        assertEquals(403, placeOrder("u1").first)
    }

    @Test
    fun `입장 토큰이 없으면 403`() {
        assertEquals(403, placeOrder("nobody").first)
        assertEquals(initialStock, stock())
    }
}
