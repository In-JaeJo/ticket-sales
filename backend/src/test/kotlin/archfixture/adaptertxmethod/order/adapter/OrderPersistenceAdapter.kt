package archfixture.adaptertxmethod.order.adapter

import org.springframework.transaction.annotation.Transactional

/** 위반: Adapter 메서드에 @Transactional. */
class OrderPersistenceAdapter {
    @Transactional
    fun save() = Unit
}
