package archfixture.good.demo

import archfixture.good.outbox.OutboxRepository
import archfixture.good.product.port.StockPort

/** demo는 의존 방향의 맨 위라 도메인과 outbox를 모두 조회할 수 있다. */
class DemoStatsCollector(
    private val stock: StockPort,
    private val outbox: OutboxRepository,
) {
    fun unpublished() = outbox.count()
}
