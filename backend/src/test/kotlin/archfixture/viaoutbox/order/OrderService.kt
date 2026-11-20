package archfixture.viaoutbox.order

import archfixture.viaoutbox.outbox.OutboxRelay

/** order → outbox는 허용이지만, 그 outbox가 payment를 알면 order가 payment에 우회 의존하게 된다. */
class OrderService(private val relay: OutboxRelay) {
    fun paid() = relay.lastPaid()
}
