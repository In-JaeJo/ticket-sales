package com.ticketsales

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

/** 통합 테스트용 MySQL·Redis·Kafka. docker-compose.yml과 같은 이미지를 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun mysql(): MySQLContainer<*> = MySQLContainer(DockerImageName.parse("mysql:8.4"))

    @Bean
    @ServiceConnection(name = "redis")
    fun redis(): GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7.4")).withExposedPorts(6379)

    @Bean
    @ServiceConnection
    fun kafka(): KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))
}
