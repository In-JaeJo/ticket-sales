package com.ticketsales

import com.ticketsales.product.DeductMode
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoApiTest : IntegrationTest() {

    @Autowired lateinit var dataSource: DataSource

    private fun postJson(path: String, json: String) =
        mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = json
        }.andReturn().response.status

    @Test
    fun `현황 API는 재고·주문·정합성을 보여준다`() {
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        eventually { assertEquals("COMPLETED", orderStatus(id!!)) }
        val body = mvc.get("/api/demo/stats").andReturn().response.contentAsString
        listOf("\"total\":$initialStock", "\"remaining\":${initialStock - 1}", "\"completed\":1", "\"oversold\":0", "\"consistent\":true")
            .forEach { kotlin.test.assertTrue(body.contains(it), "현황에 없음: $it in $body") }
    }

    @Test
    fun `PG 장애를 켜면 현황에 보이고 해소하면 사라진다`() {
        fun stats() = mvc.get("/api/demo/stats").andReturn().response.contentAsString
        assertTrue(stats().contains("\"pgDown\":false"), "처음에는 꺼져 있어야 한다")

        assertEquals(204, postJson("/api/demo/fault", """{"pgTransientErrors":1000000}"""))
        assertTrue(stats().contains("\"pgDown\":true"), "켠 뒤에는 현황에 보여야 한다")

        assertEquals(204, postJson("/api/demo/fault", """{"pgTransientErrors":0}"""))
        assertTrue(stats().contains("\"pgDown\":false"), "해소하면 사라져야 한다")
    }

    @Test
    fun `방식을 바꾸면 항상 리셋되고 새 방식의 저장소가 재고 원본이 된다`() {
        grantPass("u1")
        placeOrder("u1")
        assertEquals(1, orderCount())
        val runBefore = runs.current()
        assertEquals(204, postJson("/api/demo/mode", """{"mode":"CONDITIONAL_UPDATE"}"""))
        assertEquals(DeductMode.CONDITIONAL_UPDATE, demo.mode())
        assertEquals(runBefore + 1, runs.current())
        assertEquals(0, orderCount(), "방식 전환은 리셋과 함께만: 진행 중이던 주문도 비운다")
        assertEquals(initialStock, stock())
    }

    @Test
    fun `결제 지연은 2초를 넘게 설정할 수 없다`() {
        assertEquals(400, postJson("/api/demo/fault", """{"pgDelayMs":2500}"""))
        assertEquals(204, postJson("/api/demo/fault", """{"pgDelayMs":500}"""))
        assertEquals(500, pg.delayMs)
    }

    @Test
    fun `커넥션 풀 크기를 실행 중에 바꾼다`() {
        val hikari = dataSource.unwrap(HikariDataSource::class.java)
        try {
            assertEquals(204, postJson("/api/demo/config", """{"poolSize":15}"""))
            assertEquals(15, hikari.hikariConfigMXBean.maximumPoolSize)
        } finally {
            demo.configure(initialStock = null, admissionRate = null, poolSize = 10)
        }
    }

    @Test
    fun `가상 손님 투입 요청은 실행기가 받아 상태를 보고하고 리셋하면 사라진다`() {
        fun stat(name: String) = Regex("\"$name\":\"?([^,\"}]*)").find(mvc.get("/api/demo/stats").andReturn().response.contentAsString)!!.groupValues[1]

        assertEquals(400, postJson("/api/demo/crowd", """{"users":20000,"arrivalSeconds":3}"""))
        assertEquals(200, postJson("/api/demo/crowd", """{"users":500,"arrivalSeconds":3,"openInSeconds":30}"""))
        assertEquals("REQUESTED", stat("crowdState"))
        assertEquals("500", stat("crowdUsers"))
        assertEquals("BEFORE_OPEN", stat("salesStatus"), "오픈까지 30초로 투입하면 판매 전으로 리셋된다")
        assertTrue(stat("openInMs").toLong() in 25_000..30_000)

        // 실행기가 요청을 확인하면 살아 있다는 표시가 남는다
        val polled = mvc.get("/api/demo/crowd/request").andReturn().response
        assertEquals(200, polled.status)
        val id = Regex("\"id\":(\\d+)").find(polled.contentAsString)!!.groupValues[1].toLong()
        assertEquals(runs.current(), id)
        assertEquals("true", stat("crowdRunnerOnline"))

        assertEquals(204, postJson("/api/demo/crowd/state", """{"id":${id - 1},"state":"RUNNING"}"""))
        assertEquals("REQUESTED", stat("crowdState"), "이미 멈춘 투입의 보고는 버린다")
        assertEquals(204, postJson("/api/demo/crowd/state", """{"id":$id,"state":"RUNNING"}"""))
        assertEquals("RUNNING", stat("crowdState"))
        assertEquals(204, postJson("/api/demo/crowd/state", """{"id":$id,"state":"DONE"}"""))
        assertEquals("DONE", stat("crowdState"))

        // 리셋하면 요청이 사라져 실행기가 멈춘다. 실행기 연결 표시는 남는다. 설정 리셋은 바로 오픈한다
        assertEquals(204, postJson("/api/demo/reset", ""))
        assertEquals("IDLE", stat("crowdState"))
        assertEquals("ON_SALE", stat("salesStatus"))
        assertEquals(204, mvc.get("/api/demo/crowd/request").andReturn().response.status)
        assertEquals("true", stat("crowdRunnerOnline"))

        assertEquals(200, postJson("/api/demo/crowd", """{"users":500,"arrivalSeconds":3}"""))
        assertEquals(204, postJson("/api/demo/crowd/stop", ""))
        assertEquals("IDLE", stat("crowdState"))
    }

    @Test
    fun `Kafka 발행 중단을 켜면 Outbox에 쌓이고 끄면 발행된다`() {
        assertEquals(204, postJson("/api/demo/fault", """{"kafkaPaused":true}"""))
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        Thread.sleep(1000)
        assertEquals(1, unpublished())

        assertEquals(204, postJson("/api/demo/fault", """{"kafkaPaused":false}"""))
        eventually { assertEquals("COMPLETED", orderStatus(id!!)) }
    }
}
