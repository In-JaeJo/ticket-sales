package com.ticketsales.order.adapter

import com.ticketsales.order.port.PricingPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ConfigPricingAdapter(@Value("\${ticket.price}") private val price: Long) : PricingPort {
    override fun price(productId: Long): Long = price
}
