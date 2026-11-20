package archfixture.corejackson.product

import com.fasterxml.jackson.annotation.JsonIgnore

/** 위반: 엔티티에 Jackson 어노테이션. 직렬화는 Adapter의 DTO가 맡는다. */
class Stock(
    val productId: Long,
    @get:JsonIgnore var quantity: Int,
)
