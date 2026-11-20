package archfixture.viademo.order

import archfixture.viademo.demo.Stats

/** 위반: order가 demo를 거쳐 product 재고를 읽는다(우회 의존). */
class PlaceOrderUseCase(private val stats: Stats) {
    fun canBuy() = stats.left() > 0
}
