package com.ticketsales.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/** 운영 코드에 구조 규칙을 적용한다. 하나라도 어기면 빌드가 실패한다. */
class ArchitectureTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.ticketsales")

    @TestFactory
    fun `운영 코드는 구조 규칙을 지킨다`() =
        ArchitectureRules("com.ticketsales").all.map { rule ->
            dynamicTest(rule.description) { rule.check(classes) }
        }
}
