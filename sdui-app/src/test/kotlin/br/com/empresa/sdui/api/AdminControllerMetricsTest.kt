package br.com.empresa.sdui.api

import br.com.empresa.sdui.adapters.memory.InMemoryAuditLogStore
import br.com.empresa.sdui.adapters.memory.RecordingMetrics
import br.com.empresa.sdui.api.admin.AdminController
import br.com.empresa.sdui.api.admin.OpenPublishBody
import br.com.empresa.sdui.api.admin.RollbackBody
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.PublishRequestStatus
import br.com.empresa.sdui.orchestrator.port.inbound.DecidePublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.OpenPublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.PublishUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackCommand
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackPointerUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.lang.reflect.Proxy

/**
 * As tags das metricas administrativas vem do valor normalizado, nunca do texto cru do path ou do
 * corpo (AGENTS.md 22.10).
 *
 * `Channel.parse` aceita qualquer texto e cai em stable, e `ClientPlatform.parse` ignora caixa: a
 * operacao passa com `channel=qualquer` ou `platform=IOS`, e usar esses textos como tag abriria uma
 * serie de metrica nova por string enviada.
 */
class AdminControllerMetricsTest {
    private val metrics = RecordingMetrics()

    private val publish = object : PublishUseCase {
        override fun open(command: OpenPublishCommand): PublishRequest = PublishRequest(
            requestId = "pr_1",
            specId = command.specId,
            revision = command.revision,
            specRevisionId = "rev_1",
            surface = "home",
            platform = ClientPlatform.IOS,
            channel = command.channel,
            makerId = command.actor.id,
            status = PublishRequestStatus.OPEN,
        )

        override fun approve(command: DecidePublishCommand): PublishRequest = error("nao usado")
        override fun reject(command: DecidePublishCommand, reason: String): PublishRequest = error("nao usado")
    }

    private val rollback = object : RollbackPointerUseCase {
        override fun rollback(command: RollbackCommand): Pointer = Pointer(
            surface = command.surface,
            platform = command.platform,
            channel = command.channel,
            specId = "spec",
            specRevisionId = command.targetSpecRevisionId,
            previousSpecRevisionId = null,
            version = 2,
        )
    }

    private val controller = AdminController(
        catalogQuery = unused(),
        drafts = unused(),
        publish = publish,
        rollback = rollback,
        auditLog = InMemoryAuditLogStore(),
        metrics = metrics,
    )

    @Test
    fun `open usa o canal normalizado como tag`() {
        controller.openPublish(OpenPublishBody("spec", 1, channel = "qualquer"), headers("MAKER"), "k-1")
        controller.openPublish(OpenPublishBody("spec", 1, channel = " CANARY "), headers("MAKER"), "k-2")

        assertThat(tagsOf("admin.publish.open").map { it["channel"] }).containsExactly("stable", "canary")
    }

    @Test
    fun `rollback usa plataforma e canal normalizados como tag`() {
        controller.rollbackPointer(
            surface = "home",
            platform = "IOS",
            channel = "qualquer",
            body = RollbackBody(targetSpecRevisionId = "rev_1"),
            headers = headers("CHECKER"),
            idempotencyKey = "rb-1",
        )

        assertThat(tagsOf("admin.rollback").single())
            .isEqualTo(mapOf("surface" to "home", "platform" to "ios", "channel" to "stable"))
    }

    private fun tagsOf(name: String): List<Map<String, String>> =
        metrics.samples.filter { it.name == name }.map { it.tags }

    private fun headers(role: String): HttpHeaders = HttpHeaders().apply {
        add("Actor-Id", "actor-1")
        add("Actor-Role", role)
    }

    /** Porta que o cenario nao exercita: qualquer chamada falha o teste. */
    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("${method.name} nao usado neste teste")
        } as T
}
