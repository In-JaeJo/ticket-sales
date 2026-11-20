package com.ticketsales

import com.ticketsales.common.event.Topics
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import kotlin.test.assertTrue

/** 관측: Grafana 대시보드가 쓰는 지표가 Prometheus 엔드포인트에 나오는지, API 문서가 열리는지 */
class ObservabilityTest : IntegrationTest() {

    private fun scrape(): String = mvc.get("/actuator/prometheus").andReturn().response.contentAsString

    @Test
    fun `대시보드가 쓰는 지표가 모두 노출된다`() {
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        eventually { assertTrue(orderStatus(id!!) == "COMPLETED") }

        val metrics = scrape()
        listOf(
            "ticket_stock_remaining",
            "ticket_queue_waiting",
            "ticket_sse_connections",
            "ticket_outbox_unpublished",
            "ticket_orders{",
            "http_server_requests_seconds_bucket{",
            "hikaricp_connections_active",
            "kafka_consumer_fetch_manager_records_lag_max",
        ).forEach { name -> assertTrue(metrics.contains(name), "지표 없음: $name") }
        assertTrue(metrics.contains("uri=\"/api/orders\""), "주문 API 응답 시간 지표")
    }

    @Test
    fun `중복 메시지를 건너뛰면 건너뛴 수가 집계된다`() {
        val payload = """{"orderId":999,"userId":"x","productId":$productId,"reason":"test"}"""
        produce(Topics.ORDER_CANCELLED, "999", payload, messageId = "dup-metric")
        produce(Topics.ORDER_CANCELLED, "999", payload, messageId = "dup-metric")
        eventually { assertTrue(scrape().contains("ticket_consumer_skipped_total{") && scrape().contains("reason=\"duplicate\"")) }
    }

    @Test
    fun `API 문서가 열린다`() {
        val docs = mvc.get("/v3/api-docs").andReturn().response
        assertTrue(docs.status == 200)
        listOf("/api/orders", "/api/queue/enter", "/api/demo/reset").forEach {
            assertTrue(docs.contentAsString.contains(it), "API 문서에 없음: $it")
        }
    }
}
