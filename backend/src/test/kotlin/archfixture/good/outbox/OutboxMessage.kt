package archfixture.good.outbox

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional

@Entity
class OutboxMessage(
    val topic: String,
    val payload: String,
    val runId: Long,
) {
    @Id
    @GeneratedValue
    val id: Long = 0
}

interface OutboxRepository : JpaRepository<OutboxMessage, Long>

/** outbox는 인프라라 Adapter 규칙 대상이 아니다. @Transactional을 써도 된다. */
class OutboxRelay(private val repository: OutboxRepository) {
    @Transactional
    fun relay() = repository.findAll().size
}
