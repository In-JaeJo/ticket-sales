package com.ticketsales.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertTrue

/**
 * 규칙 자체를 검증한다.
 * 예제는 archfixture 패키지에 있다. com.ticketsales 아래에 두면 Spring이 앱을 띄울 때 예제의 @Service까지
 * 빈으로 등록해 실제 빈과 이름이 겹치므로, 일부러 앱 패키지 바깥에 둔다.
 * - 위반 예제(archfixture.*): 해당 규칙이 반드시 잡아야 한다. 한 번도 실패하지 않는 규칙은 아무것도 지키지 않는다.
 * - 올바른 예제(archfixture.good): 설계대로 만든 코드라 모든 규칙을 통과해야 한다. 규칙이 정상 코드를 막으면 안 된다.
 */
class ArchitectureRulesSelfTest {

    private fun load(name: String) = ClassFileImporter().importPackages("archfixture.$name").also {
        check(!it.isEmpty()) { "예제가 비어 있음: archfixture.$name" }
    }

    private val violations: List<Triple<String, String, (ArchitectureRules) -> ArchRule>> = listOf(
        Triple("crossdomain", "도메인이 다른 도메인을 직접 참조", { it.dependencyDirection }),
        Triple("viademo", "도메인이 demo를 거쳐 다른 도메인을 참조", { it.dependencyDirection }),
        Triple("viaoutbox", "outbox가 도메인을 참조하고 도메인이 그 outbox를 사용", { it.dependencyDirection }),
        Triple("coreredis", "UseCase가 Redis 타입을 직접 사용", { it.coreAllowList }),
        Triple("corejackson", "엔티티에 Jackson 어노테이션", { it.coreAllowList }),
        Triple("coreadapter", "UseCase가 Port를 건너뛰고 Adapter 구현체를 사용", { it.coreAllowList }),
        Triple("adaptertxclass", "Adapter 클래스에 @Transactional", { it.noTransactionalInAdapter }),
        Triple("adaptertxmethod", "Adapter 메서드에 @Transactional", { it.noTransactionalInAdapter }),
        Triple("controllerrepository", "Controller가 Repository를 직접 사용", { it.noRepositoryInController }),
        Triple("entityinevent", "이벤트가 엔티티를 담음", { it.noEntityAcrossDomains }),
    )

    @TestFactory
    fun `각 규칙은 위반 예제를 잡아낸다`() = violations.map { (name, desc, pick) ->
        dynamicTest("$name: $desc") {
            val result = pick(ArchitectureRules("archfixture.$name")).evaluate(load(name))
            assertTrue(result.hasViolation(), "규칙이 위반을 잡지 못함: $desc")
        }
    }

    @Test
    fun `설계대로 만든 코드는 모든 규칙을 통과한다`() {
        val classes = load("good")
        ArchitectureRules("archfixture.good").all.forEach { it.check(classes) }
    }
}
