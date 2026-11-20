package archfixture.coreadapter.product

import archfixture.coreadapter.product.adapter.RedisLuaStockAdapter

/** 위반: Port를 건너뛰고 구현체에 묶였다. 재고 차감 방식을 바꿀 수 없다. */
class DeductStockUseCase(private val adapter: RedisLuaStockAdapter) {
    fun run() = adapter.deduct(1, "u")
}
