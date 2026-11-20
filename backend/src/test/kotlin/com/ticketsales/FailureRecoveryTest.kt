package com.ticketsales

import com.ticketsales.common.event.Topics
import com.ticketsales.common.consumer.BaseConsumer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.testcontainers.DockerClientFactory
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 장애에서 살아남는지: Kafka 중단, DLQ 이후 시간 초과 취소, DLQ 재처리 */
class FailureRecoveryTest : IntegrationTest() {

    @Autowired lateinit var kafkaContainer: KafkaContainer

    @Test
    fun `Kafka가 멈춘 동안 들어온 주문은 Outbox에 쌓였다가 재개 후 모두 발행된다`() {
        val docker = DockerClientFactory.instance().client()
        grantPass("u1")
        docker.pauseContainerCmd(kafkaContainer.containerId).exec()
        val id = try {
            val (status, id) = placeOrder("u1")
            assertEquals(200, status, "Kafka가 멈춰도 주문은 받는다")
            Thread.sleep(3000)
            assertTrue(unpublished() >= 1, "Outbox에 쌓여 있어야 한다")
            assertEquals("PENDING", orderStatus(id!!))
            id
        } finally {
            docker.unpauseContainerCmd(kafkaContainer.containerId).exec()
        }
        eventually(90) {
            assertEquals("COMPLETED", orderStatus(id))
            assertEquals(0, unpublished())
        }
    }

    @Test
    fun `결제 메시지가 DLQ로 가도 시간 초과로 주문이 취소되고 재고가 돌아온다`() {
        pg.transientErrors.set(100)
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        eventually { assertEquals(1, countRecords("${Topics.ORDER_CREATED}.DLT", id.toString())) }
        assertEquals(initialStock - 1, stock())

        timeoutScheduler.timeout = Duration.ofSeconds(1)

        eventually {
            assertEquals("CANCELLED", orderStatus(id!!))
            assertEquals(initialStock, stock())
        }
    }

    @Test
    fun `DLQ 재처리 버튼을 누르면 DLQ의 메시지가 다시 처리된다`() {
        pg.transientErrors.set(4)
        grantPass("u1")
        val (_, id) = placeOrder("u1")
        eventually { assertEquals(1, countRecords("${Topics.ORDER_CREATED}.DLT", id.toString())) }
        assertEquals("PENDING", orderStatus(id!!))

        val replayed = demo.replayDlq()

        assertTrue(replayed >= 1)
        eventually { assertEquals("COMPLETED", orderStatus(id)) }
    }

    @Test
    fun `멱등 키를 지우지 못해 처리 중 표시가 남은 메시지도 DLQ 재처리로 다시 처리된다`() {
        // 처리 중 예외가 났는데 Redis 장애로 키를 지우지 못했다: 이 프로세스의 "처리 중" 표시가 남은 채 DLQ로 갔다
        redis.opsForValue().set("processed:stuck-1", BaseConsumer.PROCESSING, Duration.ofHours(24))
        produce(
            "${Topics.ORDER_CANCELLED}.DLT", "995",
            """{"orderId":995,"userId":"s","productId":$productId,"reason":"test"}""",
            messageId = "stuck-1",
        )

        demo.replayDlq()

        // 재처리한 메시지가 "중복"으로 건너뛰어지지 않고 재고를 되돌려야 한다
        eventually(15) { assertEquals(initialStock + 1, stock()) }
    }

    @Test
    fun `처리 중 표시를 남긴 채 실패한 메시지를 같은 앱이 다시 받으면 이어받아 처리한다`() {
        // 처리에 실패했는데 그 순간 Redis가 끊겨 키를 지우지 못했고, Redis는 곧 돌아왔다: 이 프로세스의 "처리 중" 표시가 남았다
        redis.opsForValue().set("processed:stuck-2", BaseConsumer.PROCESSING, Duration.ofHours(24))

        // Kafka 재시도로 같은 메시지가 다시 온다. 지금 처리 중인 것이 아니므로 중복으로 건너뛰면 안 된다
        produce(
            Topics.ORDER_CANCELLED, "994",
            """{"orderId":994,"userId":"t","productId":$productId,"reason":"test"}""",
            messageId = "stuck-2",
        )

        eventually(15) { assertEquals(initialStock + 1, stock()) }
    }
}
