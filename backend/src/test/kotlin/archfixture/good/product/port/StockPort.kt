package archfixture.good.product.port

import archfixture.good.common.contract.DeductResult

interface StockPort {
    val transactional: Boolean
    fun deduct(productId: Long, userId: String): DeductResult
    fun restore(productId: Long, userId: String)
}
