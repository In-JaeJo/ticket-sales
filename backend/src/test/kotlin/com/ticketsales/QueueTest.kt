package com.ticketsales

import com.ticketsales.queue.AdmitUseCase
import com.ticketsales.queue.SalesStatus
import com.ticketsales.queue.SalesStatusQuery
import com.ticketsales.queue.port.PassStorePort
import com.ticketsales.queue.port.QueueStorePort
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 대기열: 줄 서기 케이스, 판매 상태와 SSE 알림 */
class QueueTest : IntegrationTest() {

    @Autowired lateinit var admit: AdmitUseCase
    @Autowired lateinit var salesStatus: SalesStatusQuery
    @Autowired lateinit var queue: QueueStorePort
    @Autowired lateinit var passes: PassStorePort

    private data class Entered(val status: Int, val body: String) {
        val ticket: Long? get() = Regex("\"ticket\":(\\d+)").find(body)?.groupValues?.get(1)?.toLong()
        val admitted: Boolean get() = body.contains("\"admitted\":true")
    }

    private fun enter(userId: String): Entered =
        mvc.post("/api/queue/enter") { header("X-User-Id", userId) }.andReturn().response
            .let { Entered(it.status, it.contentAsString) }

    private fun status() = salesStatus.current(productId)

    @Test
    fun `오픈 전에는 줄에 넣지 않고 정각이 되면 받는다`() {
        demo.reset(Duration.ofSeconds(2))
        assertEquals(SalesStatus.BEFORE_OPEN, status())
        val early = enter("u1")
        assertEquals(409, early.status)
        assertTrue(early.body.contains("NOT_OPEN"), early.body)
        assertEquals(0, queue.waitingCount())

        eventually { assertEquals(SalesStatus.ON_SALE, status()) }
        assertEquals(200, enter("u2").status)
    }

    @Test
    fun `같은 사용자가 줄 서기를 두 번 요청하면 기존 번호표를 돌려준다`() {
        val first = enter("u1")
        val second = enter("u1")
        assertEquals(200, first.status)
        assertEquals(first.ticket, second.ticket)
    }

    @Test
    fun `앞사람이 이탈하면 그 자리를 건너뛰고 다음 대기자가 입장한다`() {
        val a = enter("a").ticket!!
        enter("b")
        val c = enter("c").ticket!!
        mvc.delete("/api/queue/leave") { header("X-User-Id", "b") }.andExpect { status { isNoContent() } }

        assertEquals(2, admit.admit(2, productId))

        assertTrue(passes.has("a"))
        assertTrue(passes.has("c"))
        assertFalse(passes.has("b"))
        assertEquals(c, queue.cursor())
        assertTrue(a < c)
    }

    @Test
    fun `입장 토큰이 있는 사용자가 다시 줄 서면 이미 입장함으로 응답한다`() {
        grantPass("u1")
        assertTrue(enter("u1").admitted)
    }

    @Test
    fun `주문한 적이 있는 사용자는 줄에 넣지 않는다`() {
        grantPass("u1")
        assertEquals(200, placeOrder("u1").first)
        val again = enter("u1")
        assertEquals(409, again.status)
        assertTrue(again.body.contains("ALREADY_ORDERED"))
    }

    @Test
    fun `최종 매진 뒤에는 줄에 넣지 않는다`() {
        demo.configure(initialStock = 1, admissionRate = null, poolSize = null)
        demo.reset()
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        eventually { assertEquals("COMPLETED", orderStatus(id!!)) }
        assertEquals(SalesStatus.FINAL_SOLD_OUT, status())

        val late = enter("u2")
        assertEquals(409, late.status)
        assertTrue(late.body.contains("SALE_ENDED"))
    }

    @Test
    fun `요청 제한을 넘으면 429`() {
        val statuses = (1..6).map { enter("burst").status }
        assertEquals(429, statuses.last())
    }

    @Test
    fun `입장 토큰을 받은 뒤 이탈하면 토큰도 지운다`() {
        grantPass("u1")
        mvc.delete("/api/queue/leave") { header("X-User-Id", "u1") }
        assertEquals(403, placeOrder("u1").first)
    }

    @Test
    fun `판매 상태는 판매 전 → 판매 중 → 매진 → 판매 중 → 최종 매진으로 바뀌고 SSE로 알린다`() {
        demo.configure(initialStock = 1, admissionRate = null, poolSize = null)
        demo.reset(Duration.ofSeconds(2))
        val stream: MvcResult = mvc.get("/api/queue/stream").andReturn()

        // 오픈 전: 연결 직후 snapshot에 판매 전과 오픈까지 남은 시간이 오고, 대기자가 있어도 입장시키지 않는다
        val snapshot = stream.response.contentAsString
        assertTrue(snapshot.contains("\"status\":\"BEFORE_OPEN\""), snapshot)
        val openInMs = Regex("\"openInMs\":(\\d+)").find(snapshot)!!.groupValues[1].toLong()
        assertTrue(openInMs in 1..2000, "오픈까지 남은 시간: $openInMs")
        queue.enter("early")
        assertEquals(0, admit.admit(10, productId))
        queue.leave("early")
        eventually { assertEquals(SalesStatus.ON_SALE, status()) }
        Thread.sleep(600)

        // 결제가 3초 걸리다 실패한다 → 그동안은 재고 0 + 결제 대기 1 = 매진(취소표 대기)
        pg.delayMs = 3000
        pg.failureRate = 1.0
        grantPass("u1")
        placeOrder("u1")
        eventually { assertEquals(SalesStatus.SOLD_OUT, status()) }

        // 매진 중에는 줄은 설 수 있지만 입장시키지 않는다
        assertEquals(200, enter("u3").status)
        assertEquals(0, admit.admit(10, productId))
        assertFalse(passes.has("u3"))

        // 결제 실패 → 취소 → 재고 복구 → 판매 중
        eventually { assertEquals(SalesStatus.ON_SALE, status()) }
        pg.delayMs = 0
        pg.failureRate = 0.0
        Thread.sleep(600) // SSE는 0.2초마다 묶어 보내므로, 그보다 짧은 상태는 원래 전송되지 않는다

        // 기다리던 u3가 입장해서 구매 → 재고 0 + 결제 대기 0 = 최종 매진
        assertEquals(1, admit.admit(10, productId))
        val (_, id) = placeOrder("u3")
        eventually { assertEquals("COMPLETED", orderStatus(id!!)) }
        assertEquals(SalesStatus.FINAL_SOLD_OUT, status())

        eventually {
            // snapshot이 아니라 상태 변경 이벤트만, 일어난 순서대로
            val changes = Regex("event:status\\ndata:\\{\"status\":\"(\\w+)\"\\}")
                .findAll(stream.response.contentAsString).map { it.groupValues[1] }.toList()
            assertEquals("ON_SALE", changes.firstOrNull(), "정각에 판매 중 알림: $changes")
            // u3 구매 중에도 잠깐 매진이 생길 수 있어서 이후는 순서만 확인한다
            val soldOut = changes.indexOf("SOLD_OUT")
            assertTrue(soldOut >= 0, "매진 알림: $changes")
            assertTrue("ON_SALE" in changes.drop(soldOut + 1), "매진 뒤 판매 중 복귀 알림: $changes")
            assertEquals("FINAL_SOLD_OUT", changes.lastOrNull(), "마지막은 최종 매진: $changes")
        }
    }
}
