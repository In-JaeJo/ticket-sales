package com.ticketsales.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.CompositeArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import com.tngtech.archunit.library.Architectures.layeredArchitecture

/**
 * ARCHITECTURE.md 8장의 구조 규칙.
 * 기준 패키지를 인자로 받아, 운영 코드와 규칙 검증용 예제에 같은 규칙을 적용한다.
 */
class ArchitectureRules(private val base: String) {

    private val domains = listOf("queue", "product", "order", "payment")
    private val transactional = "org.springframework.transaction.annotation.Transactional"

    private fun pkg(name: String) = "$base.$name.."

    private val controllers = DescribedPredicate.describe<JavaClass>("controllers") {
        it.simpleName.endsWith("Controller") ||
            it.isAnnotatedWith("org.springframework.web.bind.annotation.RestController") ||
            it.isAnnotatedWith("org.springframework.stereotype.Controller")
    }

    /** 의존은 demo → 도메인 → outbox → common 한 방향. 도메인끼리 직접 의존 금지 */
    val dependencyDirection: ArchRule = CompositeArchRule.of(
        listOf<ArchRule>(
            layeredArchitecture().consideringOnlyDependenciesInLayers().withOptionalLayers(true)
                .layer("demo").definedBy(pkg("demo"))
                .layer("domain").definedBy(*domains.map(::pkg).toTypedArray())
                .layer("outbox").definedBy(pkg("outbox"))
                .layer("common").definedBy(pkg("common"))
                .whereLayer("demo").mayNotBeAccessedByAnyLayer()
                .whereLayer("domain").mayOnlyBeAccessedByLayers("demo")
                .whereLayer("outbox").mayOnlyBeAccessedByLayers("domain", "demo")
                .whereLayer("common").mayOnlyBeAccessedByLayers("domain", "outbox", "demo")
                .allowEmptyShould(true),
        ) + domains.map { d ->
            noClasses().that().resideInAPackage(pkg(d))
                .should().dependOnClassesThat().resideInAnyPackage(*(domains - d).map(::pkg).toTypedArray())
                .allowEmptyShould(true)
        },
    ).`as`("의존은 demo → 도메인 → outbox → common 한 방향, 도메인끼리 직접 의존 금지")

    /** 도메인 핵심(루트 + port)은 허용 목록만 의존 */
    val coreAllowList: ArchRule = CompositeArchRule.of(
        domains.map { d ->
            classes().that().resideInAnyPackage("$base.$d", "$base.$d.port")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "kotlin..", "org.jetbrains.annotations..",
                    "org.slf4j..",
                    "jakarta.persistence..",
                    "org.springframework.stereotype..", "org.springframework.transaction.annotation..",
                    "$base.$d", "$base.$d.port", "$base.common.contract..", "$base.common.event..",
                ).allowEmptyShould(true)
        },
    ).`as`("도메인 핵심(엔티티·UseCase·Port)은 허용 목록만 의존")

    /** Adapter에는 @Transactional 금지 (클래스·메서드 모두) */
    val noTransactionalInAdapter: ArchRule = CompositeArchRule.of(
        noClasses().that().resideInAPackage("$base..adapter..")
            .should().beAnnotatedWith(transactional).allowEmptyShould(true),
    ).and(
        noMethods().that().areDeclaredInClassesThat().resideInAPackage("$base..adapter..")
            .should().beAnnotatedWith(transactional).allowEmptyShould(true),
    ).`as`("Adapter에는 @Transactional 금지 (클래스·메서드 모두)")

    /** Controller는 Repository를 직접 쓰지 않는다 */
    val noRepositoryInController: ArchRule = noClasses().that().resideInAPackage("$base..").and(controllers)
        .should().dependOnClassesThat().areAssignableTo("org.springframework.data.repository.Repository")
        .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
        .allowEmptyShould(true)
        .`as`("Controller는 Repository 직접 사용 금지")

    /** 도메인 경계를 넘는 계약·이벤트에는 엔티티를 넣지 않는다 */
    val noEntityAcrossDomains: ArchRule = noClasses().that().resideInAnyPackage("$base.common.contract..", "$base.common.event..")
        .should().dependOnClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
        .allowEmptyShould(true)
        .`as`("계약·이벤트에 엔티티 금지")

    val all: List<ArchRule> = listOf(dependencyDirection, coreAllowList, noTransactionalInAdapter, noRepositoryInController, noEntityAcrossDomains)
}
