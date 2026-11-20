package com.ticketsales

import org.junit.jupiter.api.Test

/** 실제 MySQL·Redis·Kafka에 붙어 컨텍스트가 빈 이름 충돌 없이 기동한다. */
class ApplicationBootTest : IntegrationTest() {
    @Test
    fun `컨텍스트가 기동한다`() = Unit
}
