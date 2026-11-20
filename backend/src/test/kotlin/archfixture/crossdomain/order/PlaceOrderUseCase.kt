package archfixture.crossdomain.order

import archfixture.crossdomain.product.Stock

/** 위반: order가 product를 직접 참조한다. */
class PlaceOrderUseCase(private val stock: Stock) {
    fun left() = stock.quantity
}
