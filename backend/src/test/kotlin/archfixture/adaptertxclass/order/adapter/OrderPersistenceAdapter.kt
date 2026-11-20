package archfixture.adaptertxclass.order.adapter

import org.springframework.transaction.annotation.Transactional

/** 위반: Adapter 클래스에 @Transactional. 트랜잭션은 UseCase 계층에서 시작한다. */
@Transactional
class OrderPersistenceAdapter {
    fun save() = Unit
}
