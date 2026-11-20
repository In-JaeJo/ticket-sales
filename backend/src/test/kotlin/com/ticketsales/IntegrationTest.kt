package com.ticketsales

import com.ticketsales.common.config.RedisRunAdapter
import com.ticketsales.common.consumer.MessageHeaders
import com.ticketsales.demo.DemoService
import com.ticketsales.order.adapter.PendingOrderTimeoutScheduler
import com.ticketsales.outbox.OutboxRelay
import com.ticketsales.product.DeductMode
import com.ticketsales.payment.adapter.FakePgAdapter
import com.ticketsales.product.port.StockPortSelector
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.util.UUID

/**
 * 통합 테스트 공통 바탕. 모든 테스트가 같은 컨텍스트와 같은 컨테이너를 쓴다.
 * 테스트마다 리셋처럼 판 번호를 올리고 나머지를 비운다. 앞 테스트의 메시지가 남아 있어도 판 번호로 걸러진다.
 */
@SpringBootTest(
    properties = [
        "ticket.retry.initial-interval-ms=100",
        "ticket.retry.multiplier=1.0",
        "ticket.outbox.poll-ms=50",
    ],
)
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(TestcontainersConfiguration::class, FaultInjectionConfiguration::class)
abstract class IntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var runs: RedisRunAdapter
    @Autowired lateinit var stocks: StockPortSelector
    @Autowired lateinit var pg: FakePgAdapter
    @Autowired lateinit var faults: Faults
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var demo: DemoService
    @Autowired lateinit var relay: OutboxRelay
    @Autowired lateinit var timeoutScheduler: PendingOrderTimeoutScheduler
    /** Testcontainers가 넘긴 연결 정보. 설정값 spring.kafka.bootstrap-servers는 기본값(localhost:9092) 그대로라 쓰면 안 된다. */
    @Autowired lateinit var kafkaConnection: KafkaConnectionDetails
    val bootstrap: String get() = kafkaConnection.bootstrapServers.joinToString(",")

    val productId = 1L
    val initialStock = 10

    /** 테스트마다 데모 리셋으로 초기화한다. 판 번호가 올라가므로 앞 테스트의 메시지가 남아 있어도 걸러진다. */
    @BeforeEach
    fun resetState() {
        demo.configure(initialStock = initialStock, admissionRate = 0, poolSize = null)
        relay.paused = false
        timeoutScheduler.timeout = Duration.ofMinutes(5)
        demo.changeMode(DeductMode.REDIS_LUA)
        pg.failureRate = 0.0
        pg.delayMs = 0
        faults.clear()
    }

    fun grantPass(vararg userIds: String) {
        mvc.post("/api/demo/pass") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userIds":[${userIds.joinToString(",") { "\"$it\"" }}]}"""
        }.andExpect { status { isOk() } }
    }

    /** 주문을 넣고 (HTTP 상태, 주문 ID 또는 null) */
    fun placeOrder(userId: String): Pair<Int, Long?> {
        val result = mvc.post("/api/orders") {
            header("X-User-Id", userId)
            contentType = MediaType.APPLICATION_JSON
            content = """{"productId":$productId}"""
        }.andReturn().response
        val id = Regex("\"orderId\":(\\d+)").find(result.contentAsString)?.groupValues?.get(1)?.toLong()
        return result.status to id
    }

    fun stock() = stocks.current().remaining(productId)
    fun orderStatus(id: Long): String? =
        jdbc.queryForList("SELECT status FROM orders WHERE id = ?", String::class.java, id).firstOrNull()
    fun orderCount(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Int::class.java)!!
    fun outboxCount(topic: String, key: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE topic = ? AND message_key = ?", Int::class.java, topic, key)!!

    fun produce(topic: String, key: String, payload: String, messageId: String = UUID.randomUUID().toString(), runId: Long = runs.current()) {
        val record = ProducerRecord(topic, key, payload).apply {
            headers().add(MessageHeaders.MESSAGE_ID, messageId.toByteArray())
            headers().add(MessageHeaders.RUN_ID, runId.toString().toByteArray())
        }
        kafka.send(record).get()
    }

    /**
     * 토픽에서 이 키를 가진 레코드 수. 컨슈머 그룹 없이 파티션을 직접 지정해 처음부터 끝 오프셋까지 읽는다.
     * (새 그룹은 브로커가 배정을 몇 초 미루므로 짧은 대기로는 아무것도 못 읽는다)
     */
    fun countRecords(topic: String, key: String): Int {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
        )
        KafkaConsumer<String, String>(props).use { consumer ->
            val partitions = consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
            consumer.assign(partitions)
            consumer.seekToBeginning(partitions)
            val end = consumer.endOffsets(partitions)
            var count = 0
            val deadline = System.currentTimeMillis() + 10_000
            while (partitions.any { consumer.position(it) < end.getValue(it) } && System.currentTimeMillis() < deadline) {
                count += consumer.poll(Duration.ofMillis(200)).count { it.key() == key }
            }
            return count
        }
    }

    fun unpublished(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL", Int::class.java)!!

    fun eventually(seconds: Long = 30, block: () -> Unit) = await().atMost(Duration.ofSeconds(seconds)).untilAsserted(block)
}
