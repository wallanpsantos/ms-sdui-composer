@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.api

import br.com.empresa.sdui.SduiAppTestConfiguration
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.port.inbound.DraftSpecCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.OpenPublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.PublishUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackCommand
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackPointerUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.json.JsonMapper
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@SpringBootTest(classes = [SduiAppTestConfiguration::class])
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminGovernanceWebTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val drafts: DraftUseCase,
    @Autowired private val publish: PublishUseCase,
    @Autowired private val rollback: RollbackPointerUseCase,
    @Autowired private val specStore: SpecStore,
    @Autowired private val pointerStore: PointerStore,
    @Autowired private val catalogStore: CatalogStore,
    @Autowired private val auditLog: AuditLogStore,
    @Autowired private val treeCache: HydratedScreenCache,
) {
    @Test
    fun `catalogo ativo tem exatamente sete types em 1 e skeleton na ordem do contrato`() {
        val catalog = mockMvc.get("/admin/v1/catalog/components") {
            header("Actor-Id", "auditor-1")
            header("Actor-Role", "AUDITOR")
        }.andReturn()
        assertThat(catalog.response.status).isEqualTo(200)
        val types = jsonMapper.readTree(catalog.response.contentAsString).get("components")
        val wires = (0 until types.size()).map {
            "${types.get(it).get("type").asText()}@${types.get(it).get("typeVersion").asInt()}"
        }
        assertThat(wires).containsExactlyInAnyOrder(
            "top_bar@1", "shortcut_shelf@1", "account_card@1", "card_product@1",
            "credit_offer@1", "coverage_card@1", "decision_card@1",
        )
        val skeleton = mockMvc.get("/admin/v1/skeletons/home.default") {
            header("Actor-Id", "auditor-1")
            header("Actor-Role", "AUDITOR")
        }.andReturn()
        val slots = jsonMapper.readTree(skeleton.response.contentAsString).get("slots")
        val ids = (0 until slots.size()).map { slots.get(it).get("id").asText() }
        assertThat(ids).containsExactly("header", "shortcuts", "accounts", "cards", "offers", "coverage", "foryou")
        assertThat(slots.get(0).get("required").asBoolean()).isTrue()
        assertThat(catalogStore.current().components).hasSize(7)
    }

    @Test
    fun `rascunho recusa CSS, type generico, placement invalido e slot required vazio`() {
        val current = specStore.findByRevisionId("rev_01K8HOMEMAIN")!!
        val withCss = current.copy(
            specId = "spec_home_ios_draft_css",
            revision = 1,
            specRevisionId = "rev_css",
            status = SpecStatus.DRAFT,
            sections = current.sections.map { it.copy(props = it.props + ("color" to "#fff")) },
        )
        assertThatThrownBy {
            drafts.createSpecDraft(DraftSpecCommand(Actor("maker-1", ActorRole.MAKER), withCss))
        }.hasMessageContaining("color")

        val generic = current.copy(
            specId = "spec_home_ios_draft_generic",
            revision = 1,
            specRevisionId = "rev_generic",
            status = SpecStatus.DRAFT,
            sections = current.sections + current.sections.first().copy(id = "sec_row", type = "row", slot = "foryou"),
        )
        assertThatThrownBy {
            drafts.createSpecDraft(DraftSpecCommand(Actor("maker-1", ActorRole.MAKER), generic))
        }

        val badSlot = current.copy(
            specId = "spec_home_ios_draft_slot",
            revision = 1,
            specRevisionId = "rev_slot",
            status = SpecStatus.DRAFT,
            sections = current.sections + current.sections.first().copy(id = "sec_bad", slot = "inexistente"),
        )
        assertThatThrownBy {
            drafts.createSpecDraft(DraftSpecCommand(Actor("maker-1", ActorRole.MAKER), badSlot))
        }.hasMessageContaining("inexistente")

        val emptyRequired = current.copy(
            specId = "spec_home_ios_draft_required",
            revision = 1,
            specRevisionId = "rev_required",
            status = SpecStatus.DRAFT,
            sections = current.sections.filter { it.slot != "accounts" }.map {
                if (it.slot == "accounts") it else it
            }.filter { it.slot != "accounts" },
        )
        assertThatThrownBy {
            drafts.createSpecDraft(DraftSpecCommand(Actor("maker-1", ActorRole.MAKER), emptyRequired))
        }.hasMessageContaining("required")
    }

    @Test
    fun `maker nao aprova o proprio pedido, approve concorrente 409 e rollback restaura specRevisionId`() {
        val current = specStore.findByRevisionId("rev_01K8HOMEMAIN")!!
        val draft = drafts.createSpecDraft(
            DraftSpecCommand(
                Actor("maker-1", ActorRole.MAKER),
                current.copy(
                    specId = "spec_home_ios_gov",
                    revision = specStore.nextRevision("spec_home_ios_gov"),
                    specRevisionId = "rev_gov_${UUID.randomUUID()}",
                    status = SpecStatus.DRAFT,
                    parentRevision = 1,
                ),
            ),
        )
        val opened = publish.open(
            OpenPublishCommand(Actor("maker-1", ActorRole.MAKER), draft.specId, draft.revision, Channel.STABLE, null),
        )
        val self = mockMvc.post("/admin/v1/publish-requests/${opened.requestId}/approve") {
            header("Actor-Id", "maker-1")
            header("Actor-Role", "CHECKER")
            header("Idempotency-Key", "self-approve")
            contentType = MediaType.APPLICATION_JSON
        }.andReturn()
        assertThat(self.response.status).isEqualTo(403)

        val key = "approve-${opened.requestId}"
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val tasks = (1..2).map {
            Callable {
                mockMvc.post("/admin/v1/publish-requests/${opened.requestId}/approve") {
                    header("Actor-Id", "checker-2")
                    header("Actor-Role", "CHECKER")
                    header("Idempotency-Key", "$key-$it")
                    contentType = MediaType.APPLICATION_JSON
                }.andReturn().response.status
            }
        }
        val statuses = pool.invokeAll(tasks).map { it.get() }
        pool.close()
        assertThat(statuses).contains(200)
        assertThat(statuses).contains(409)

        val published = specStore.findBySpecIdAndRevision(draft.specId, draft.revision)!!
        assertThat(published.status).isEqualTo(SpecStatus.PUBLISHED)
        val previousPointer = pointerStore.find(MvpCatalog.SURFACE_HOME, ClientPlatform.IOS, Channel.STABLE)!!
        pointerStore.save(
            previousPointer.copy(
                specRevisionId = published.specRevisionId,
                specId = published.specId,
                previousSpecRevisionId = "rev_01K8HOMEMAIN"
            )
        )
        (treeCache as InMemoryHydratedScreenCache).clear()

        val makerRollback = mockMvc.post("/admin/v1/pointers/home/ios/stable:rollback") {
            header("Actor-Id", "maker-1")
            header("Actor-Role", "MAKER")
            header("Idempotency-Key", "rb-maker")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"nope"}"""
        }.andReturn()
        assertThat(makerRollback.response.status).isEqualTo(403)

        rollback.rollback(
            RollbackCommand(
                actor = Actor("checker-2", ActorRole.CHECKER),
                surface = "home",
                platform = ClientPlatform.IOS,
                channel = Channel.STABLE,
                targetSpecRevisionId = "rev_01K8HOMEMAIN",
                idempotencyKey = "rb-1",
                reason = "restore",
            ),
        )
        rollback.rollback(
            RollbackCommand(
                actor = Actor("checker-2", ActorRole.CHECKER),
                surface = "home",
                platform = ClientPlatform.IOS,
                channel = Channel.STABLE,
                targetSpecRevisionId = "rev_01K8HOMEMAIN",
                idempotencyKey = "rb-1",
                reason = "restore",
            ),
        )
        treeCache.clear()
        val composed = mockMvc.get("/v1/surfaces/home") {
            CanonicalHeaders.ios().forEach { (n, v) -> header(n, v) }
        }.andReturn()
        assertThat(
            jsonMapper.readTree(composed.response.contentAsString).get("envelope").get("specRevisionId").asText()
        )
            .isEqualTo("rev_01K8HOMEMAIN")
        assertThat(auditLog.list().map { it.action }).contains("publish.approve", "pointer.rollback")
        val rollbackEvents = auditLog.list().count { it.action == "pointer.rollback" && it.requestId == "rb-1" }
        assertThat(rollbackEvents).isEqualTo(1)
    }

    @Test
    fun `spec publicada nao e reescrita e pointer canary e independente de stable`() {
        val published = specStore.findByRevisionId("rev_01K8HOMEMAIN")!!
        assertThatThrownBy { specStore.save(published.copy(experience = "tamper")) }
            .hasMessageContaining("imutavel")
        val stable = pointerStore.find("home", ClientPlatform.IOS, Channel.STABLE)!!
        val canary = pointerStore.find("home", ClientPlatform.IOS, Channel.CANARY)!!
        pointerStore.save(
            canary.copy(
                specRevisionId = "rev_01K8HOMENEXT",
                specId = "spec_home_ios_next",
                version = canary.version + 1
            )
        )
        val stableAfter = pointerStore.find("home", ClientPlatform.IOS, Channel.STABLE)!!
        assertThat(stableAfter.specRevisionId).isEqualTo(stable.specRevisionId)
        pointerStore.save(canary)
    }

    @Test
    fun `put de type generico no catalogo falha e put de type mvp preserva os sete types`() {
        val generic = mockMvc.put("/admin/v1/catalog/components/row/1") {
            header("Actor-Id", "maker-1")
            header("Actor-Role", "MAKER")
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"row","typeVersion":1,"status":"ACTIVE","sinceSchema":"3","requiredProps":[]}"""
        }.andReturn()
        assertThat(generic.response.status).isEqualTo(400)

        val ok = mockMvc.put("/admin/v1/catalog/components/top_bar/1") {
            header("Actor-Id", "maker-1")
            header("Actor-Role", "MAKER")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"type":"top_bar","typeVersion":1,"status":"ACTIVE","sinceSchema":"3","requiredProps":["greetingName"]}"""
        }.andReturn()
        assertThat(ok.response.status).isEqualTo(200)
        val types = jsonMapper.readTree(ok.response.contentAsString).get("components")
        assertThat(types.size()).isEqualTo(7)
    }

    @Test
    fun `publish recusa slot portante so com account_card 2 em faixa que so declara 1`() {
        val current = specStore.findByRevisionId("rev_01K8HOMEMAIN")!!
        val accountsV2 = current.sections.map { section ->
            if (section.slot == "accounts") section.copy(typeVersion = 2) else section
        }
        val draft = current.copy(
            specId = "spec_home_ios_account_v2",
            revision = 1,
            specRevisionId = "rev_account_v2",
            status = SpecStatus.DRAFT,
            sections = accountsV2,
        )
        assertThatThrownBy {
            drafts.createSpecDraft(DraftSpecCommand(Actor("maker-1", ActorRole.MAKER), draft))
        }.hasMessageMatching("(?s).*(required|catalogo|fora do catalogo).*")
    }
}
