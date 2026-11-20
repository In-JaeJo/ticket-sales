package archfixture.good.product

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Version

@Entity
class Stock(
    @Id val productId: Long,
    var quantity: Int,
) {
    @Version
    val version: Long = 0
}
