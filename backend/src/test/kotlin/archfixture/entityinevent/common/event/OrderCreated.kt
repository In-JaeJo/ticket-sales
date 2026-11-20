package archfixture.entityinevent.common.event

import jakarta.persistence.Entity
import jakarta.persistence.Id

@Entity
class OrderRecord(@Id val id: Long = 0)

/** 위반: 도메인 경계를 넘는 이벤트가 엔티티를 담는다. 원시값과 DTO만 담아야 한다. */
data class OrderCreated(val order: OrderRecord)
