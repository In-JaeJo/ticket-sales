package archfixture.good.order

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "orders")
class Order(
    val userId: String,
    val productId: Long,
    val runId: Long,
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue
    val id: Long = 0

    var pending: Boolean = true
        protected set

    fun cancel(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }
}
