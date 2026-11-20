package archfixture.controllerrepository.order.adapter

import org.springframework.data.repository.CrudRepository

class OrderRow(val id: Long)

interface OrderRepository : CrudRepository<OrderRow, Long>

/** 위반: Controller가 Repository를 직접 쓴다. UseCase를 거쳐야 한다. */
class OrderController(private val repository: OrderRepository) {
    fun count() = repository.count()
}
