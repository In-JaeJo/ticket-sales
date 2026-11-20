package archfixture.good.product

import archfixture.good.common.event.OrderCancelled
import archfixture.good.product.port.StockPort
import org.springframework.stereotype.Service

@Service
class RestoreStockUseCase(private val port: StockPort) {
    fun restore(event: OrderCancelled) = port.restore(event.productId, event.userId)
}
