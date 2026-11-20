package archfixture.good.common.event

interface DomainEvent {
    val orderId: Long
}

data class OrderCreated(
    override val orderId: Long,
    val userId: String,
    val productId: Long,
    val amount: Long,
) : DomainEvent

data class OrderCancelled(
    override val orderId: Long,
    val userId: String,
    val productId: Long,
    val reason: String,
) : DomainEvent
