package archfixture.good.order.adapter

import archfixture.good.common.event.DomainEvent
import archfixture.good.order.Order
import archfixture.good.order.PlaceOrderUseCase
import archfixture.good.order.port.PublishEventPort
import archfixture.good.order.port.SaveOrderPort
import archfixture.good.outbox.OutboxMessage
import archfixture.good.outbox.OutboxRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

interface OrderJpaRepository : JpaRepository<Order, Long>

class OrderPersistenceAdapter(private val repository: OrderJpaRepository) : SaveOrderPort {
    override fun save(order: Order) = repository.save(order)
}

/** 도메인 → outbox 의존은 허용. 직렬화(Jackson)는 Adapter에서만 한다. */
class OrderOutboxEventAdapter(
    private val repository: OutboxRepository,
    private val mapper: ObjectMapper,
) : PublishEventPort {
    override fun publish(topic: String, runId: Long, event: DomainEvent) {
        repository.save(OutboxMessage(topic, mapper.writeValueAsString(event), runId))
    }
}

/** Controller는 UseCase만 부른다. */
@RestController
class OrderController(private val useCase: PlaceOrderUseCase) {
    @PostMapping("/api/orders")
    fun place() = useCase.place("u", 1, 1)
}
