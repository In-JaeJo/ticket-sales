package archfixture.coreadapter.product.adapter

import archfixture.coreadapter.product.port.StockPort

class RedisLuaStockAdapter : StockPort {
    override fun deduct(productId: Long, userId: String) = true
}
