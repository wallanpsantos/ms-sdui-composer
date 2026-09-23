package br.com.empresa.sdui.orchestrator

import br.com.empresa.sdui.adapters.memory.InMemoryAuditLogStore
import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.orchestrator.admin.AuditQueryService
import br.com.empresa.sdui.orchestrator.port.inbound.AdminDenied
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class AuditQueryServiceTest {

    private val store = InMemoryAuditLogStore().apply {
        (1..3).forEach { append(event("e$it", Instant.ofEpochSecond(it.toLong()))) }
    }
    private val service = AuditQueryService(store)

    @Test
    fun `checker e auditor leem a trilha do mais novo para o mais antigo`() {
        listOf(ActorRole.CHECKER, ActorRole.AUDITOR).forEach { role ->
            assertThat(service.recent(Actor("a", role), 2).map { it.id }).containsExactly("e3", "e2")
        }
    }

    @Test
    fun `maker nao le a trilha de auditoria`() {
        assertThatThrownBy { service.recent(Actor("m", ActorRole.MAKER), 2) }
            .isInstanceOf(AdminDenied::class.java)
    }

    private fun event(id: String, ts: Instant) = AuditEvent(
        id = id,
        ts = ts,
        actorId = "checker-1",
        role = ActorRole.CHECKER,
        action = "approve",
        surface = "home",
        platform = ClientPlatform.IOS,
        channel = Channel.STABLE,
        specId = "spec",
        fromRevision = null,
        toRevision = "spec#1",
        requestId = null,
    )
}
