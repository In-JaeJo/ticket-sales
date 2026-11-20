package archfixture.coreadapter.product.port

interface StockPort {
    fun deduct(productId: Long, userId: String): Boolean
}
