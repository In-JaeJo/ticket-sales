package archfixture.good.common.contract

enum class DeductResult { SUCCESS, DUPLICATE, SOLD_OUT }

interface StockDeductionContract {
    fun deduct(productId: Long, userId: String): DeductResult
}
