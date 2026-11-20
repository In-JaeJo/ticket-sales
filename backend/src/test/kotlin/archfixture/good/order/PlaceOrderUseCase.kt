package archfixture.good.order

import archfixture.good.common.contract.DeductResult
import archfixture.good.common.contract.StockDeductionContract
import archfixture.good.common.event.OrderCreated
import archfixture.good.order.port.PublishEventPort
import archfixture.good.order.port.SaveOrderPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** 허용 목록만 쓰는 UseCase: common 계약·이벤트, 자기 Port, 로그, Clock, 트랜잭션·스테레오타입 어노테이션. */
@Service
class PlaceOrderUseCase(
    private val stock: StockDeductionContract,
    private val orders: SaveOrderPort,
    private val events: PublishEventPort,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun place(userId: String, productId: Long, runId: Long): Long {
        check(stock.deduct(productId, userId) == DeductResult.SUCCESS)
        val order = orders.save(Order(userId, productId, runId, clock.instant()))
        events.publish("order.created", runId, OrderCreated(order.id, userId, productId, 50_000))
        log.info("주문 {}", order.id)
        return order.id
    }
}
