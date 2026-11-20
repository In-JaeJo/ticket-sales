package com.ticketsales.order.adapter

import com.ticketsales.order.OrderRejected
import com.ticketsales.order.PlaceOrderUseCase
import com.ticketsales.order.RejectReason
import com.ticketsales.order.port.OrderStorePort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

data class PlaceOrderRequest(val productId: Long)
data class PlaceOrderResponse(val orderId: Long)
data class OrderStatusResponse(val orderId: Long, val status: String)
data class ErrorResponse(val code: String, val message: String)

@RestController
class OrderController(
    private val placeOrder: PlaceOrderUseCase,
    private val orders: OrderStorePort,
) {
    @PostMapping("/api/orders")
    fun place(@RequestHeader("X-User-Id") userId: String, @RequestBody request: PlaceOrderRequest) =
        PlaceOrderResponse(placeOrder.place(userId, request.productId))

    @GetMapping("/api/orders/{id}")
    fun get(@PathVariable id: Long): ResponseEntity<OrderStatusResponse> =
        orders.findById(id)?.let { ResponseEntity.ok(OrderStatusResponse(it.id, it.status.name)) }
            ?: ResponseEntity.notFound().build()

    @ExceptionHandler(OrderRejected::class)
    fun rejected(e: OrderRejected): ResponseEntity<ErrorResponse> = when (e.reason) {
        RejectReason.NO_PASS -> error(HttpStatus.FORBIDDEN, "NO_PASS", "입장 토큰이 없어요. 대기열에서 차례를 받아 주세요.")
        RejectReason.SOLD_OUT -> error(HttpStatus.CONFLICT, "SOLD_OUT", "매진됐어요.")
        RejectReason.DUPLICATE -> error(HttpStatus.CONFLICT, "DUPLICATE", "이미 주문한 적이 있어요. 1인 1회만 주문할 수 있어요.")
        RejectReason.BUSY -> error(HttpStatus.SERVICE_UNAVAILABLE, "BUSY", "주문이 몰리고 있어요. 잠시 후 다시 시도해 주세요.")
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun duplicate(): ResponseEntity<ErrorResponse> = error(HttpStatus.CONFLICT, "DUPLICATE", "이미 주문한 적이 있어요. 1인 1회만 주문할 수 있어요.")

    @ExceptionHandler(RedisConnectionFailureException::class)
    fun redisDown(): ResponseEntity<ErrorResponse> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "일시적인 장애가 있어요. 잠시 후 다시 시도해 주세요.")

    private fun error(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ErrorResponse(code, message))
}
