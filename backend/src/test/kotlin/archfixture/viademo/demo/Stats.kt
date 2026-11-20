package archfixture.viademo.demo

import archfixture.viademo.product.Stock

/** demo는 집계를 위해 도메인을 조회할 수 있다(허용). */
class Stats(private val stock: Stock) {
    fun left(): Int = stock.quantity
}
