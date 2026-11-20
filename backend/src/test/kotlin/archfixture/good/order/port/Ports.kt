package archfixture.good.order.port

import archfixture.good.common.event.DomainEvent
import archfixture.good.order.Order

interface SaveOrderPort {
    fun save(order: Order): Order
}

interface PublishEventPort {
    fun publish(topic: String, runId: Long, event: DomainEvent)
}
