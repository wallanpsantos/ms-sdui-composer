package br.com.empresa.sdui.api

import br.com.empresa.sdui.adapters.memory.RecordingMetrics
import br.com.empresa.sdui.api.admin.AdminController
import br.com.empresa.sdui.api.admin.OpenPublishBody
import br.com.empresa.sdui.api.admin.RollbackBody
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.PublishRequestStatus
import br.com.empresa.sdui.orchestrator.port.inbound.AdminNotFound
import br.com.empresa.sdui.orchestrator.port.inbound.AdminValidation
import br.com.empresa.sdui.orchestrator.port.inbound.DecidePublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.OpenPublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.PublishUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackCommand
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackPointerUseCase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.lang.reflect.Proxy

/**
 * As tags das metricas administrativas vem do valor normalizado, nunca do texto cru do path ou do
 * corpo (AGENTS.md 22.10), e canal ou plataforma desconhecidos sao recusados em vez de virar
 * stable.
 *
 * `Channel.parseOrNull` e `ClientPlatform.parse` ignoram caixa e espaco: a operacao passa com
 * `channel= CANARY ` ou `platform=IOS`, e usar esses textos como tag abriria uma serie de metrica
 * nova por grafia enviada.
 */
class AdminControllerMetricsTest {
    private val metrics = RecordingMetrics()
    private val opened = mutableListOf<OpenPublishCommand>()
    private val rolledBack = mutableListOf<RollbackCommand>()

    private val publish = object : PublishUseCase {
        override fun open(command: OpenPublishCommand): PublishRequest {
            opened += command
            return PublishRequest(
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
        }

        override fun approve(command: DecidePublishCommand): PublishRequest = error("nao usado")
        override fun reject(command: DecidePublishCommand, reason: String): PublishRequest = error("nao usado")
    }

    private val rollback = object : RollbackPointerUseCase {
        override fun rollback(command: RollbackCommand): Pointer {
            rolledBack += command
            return Pointer(
                surface = command.surface,
                platform = command.platform,
                channel = command.channel,
                specId = "spec",
                specRevisionId = command.targetSpecRevisionId,
                previousSpecRevisionId = null,
                version = 2,
            )
        }
    }

    private val controller = AdminController(
        catalogQuery = unused(),
        drafts = unused(),
        publish = publish,
        rollback = rollback,
        auditQuery = unused(),
        metrics = metrics,
    )

    @Test
    fun `open usa o canal normalizado como tag`() {
        controller.openPublish(OpenPublishBody("spec", 1, channel = "Stable"), headers("MAKER"), "k-1")
        controller.openPublish(OpenPublishBody("spec", 1, channel = " CANARY "), headers("MAKER"), "k-2")

        assertThat(tagsOf("admin.publish.open").map { it["channel"] }).containsExactly("stable", "canary")
    }

    @Test
    fun `open com canal desconhecido e recusado sem abrir pedido em stable`() {
        assertThatThrownBy {
            controller.openPublish(OpenPublishBody("spec", 1, channel = "canry"), headers("MAKER"), "k-3")
        }.isInstanceOf(AdminValidation::class.java)

        assertThat(opened).isEmpty()
        assertThat(tagsOf("admin.publish.open")).isEmpty()
    }

    @Test
    fun `rollback usa plataforma e canal normalizados como tag`() {
        controller.rollbackPointer(
            surface = "home",
            platform = "IOS",
            channel = "STABLE",
            body = RollbackBody(targetSpecRevisionId = "rev_1"),
            headers = headers("CHECKER"),
            idempotencyKey = "rb-1",
        )

        assertThat(tagsOf("admin.rollback").single())
            .isEqualTo(mapOf("surface" to "home", "platform" to "ios", "channel" to "stable"))
    }

    @Test
    fun `rollback com canal ou plataforma desconhecidos e 404 sem mover pointer`() {
        for ((platform, channel) in listOf("ios" to "qualquer", "web" to "stable")) {
            assertThatThrownBy {
                controller.rollbackPointer(
                    surface = "home",
                    platform = platform,
                    channel = channel,
                    body = RollbackBody(targetSpecRevisionId = "rev_1"),
                    headers = headers("CHECKER"),
                    idempotencyKey = "rb-$platform-$channel",
                )
            }.isInstanceOf(AdminNotFound::class.java)
        }

        assertThat(rolledBack).isEmpty()
        assertThat(tagsOf("admin.rollback")).isEmpty()
    }

    @Test
    fun `filtro de listagem desconhecido e recusado em vez de listar tudo`() {
        for ((platform, channel) in listOf("web" to null, null to "canry")) {
            assertThatThrownBy {
                controller.specs(platform, channel, offset = null, limit = null, headers = headers("AUDITOR"))
            }.isInstanceOf(AdminValidation::class.java)
        }
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
