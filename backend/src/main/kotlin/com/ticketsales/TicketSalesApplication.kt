package com.ticketsales

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TicketSalesApplication

fun main(args: Array<String>) {
    runApplication<TicketSalesApplication>(*args)
}
