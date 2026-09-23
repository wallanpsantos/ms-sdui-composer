package br.com.empresa.sdui.orchestrator

import br.com.empresa.sdui.adapters.memory.RecordingMetrics
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotDefinition
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.compose.HydrationContext
import br.com.empresa.sdui.orchestrator.compose.HydrationResult
import br.com.empresa.sdui.orchestrator.compose.SectionHydrator
import br.com.empresa.sdui.orchestrator.hydration.HydrationCoordinator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HydrationCoordinatorTest {
    @Test
    fun `hidrata sections em paralelo e omite timeout sem derrubar as demais`() {
        val entered = CountDownLatch(2)
        val slow = object : SectionHydrator {
            override fun supports(type: String, typeVersion: Int): Boolean = type == "decision_card"
            override fun hydrate(context: HydrationContext, section: Section): HydrationResult {
                entered.countDown()
                entered.await(1, TimeUnit.SECONDS)
                Thread.sleep(400)
                return HydrationResult.Ok(section.props)
            }
        }
        val fast = object : SectionHydrator {
            override fun supports(type: String, typeVersion: Int): Boolean = type != "decision_card"
            override fun hydrate(context: HydrationContext, section: Section): HydrationResult {
                entered.countDown()
                entered.await(1, TimeUnit.SECONDS)
                return HydrationResult.Ok(section.props + ("hydrated" to true))
            }
        }
        val coordinator = HydrationCoordinator(
            hydrators = listOf(slow, fast),
            fanOut = Semaphore(8),
            timeout = Duration.ofMillis(80),
            metrics = RecordingMetrics(),
        )
        val result = coordinator.hydrate(
            context = HydrationContext(
                surface = "home",
                platform = ClientPlatform.IOS,
                specRevisionId = "rev_x",
                locale = "pt-BR",
                channel = Channel.STABLE,
            ),
            skeleton = skeleton(),
            sections = listOf(
                section("sec_header_1", "header", "top_bar"),
                section("sec_foryou_1", "foryou", "decision_card"),
            ),
            alreadyOmitted = emptyList(),
        )
        assertThat(result.sections.map { it.id }).containsExactly("sec_header_1")
        assertThat(result.sections.single().props["hydrated"]).isEqualTo(true)
        assertThat(result.omitted).hasSize(1)
        assertThat(result.omitted.single().id).isEqualTo("sec_foryou_1")
        assertThat(result.omitted.single().reason).isEqualTo(OmittedReason.HYDRATION_TIMEOUT)
        assertThat(result.requiredSlotFailed).isFalse()
    }

    @Test
    fun `hidratador sem IO roda na thread da requisicao, com a mesma telemetria e omissao`() {
        val metrics = RecordingMetrics()
        val tasks = AtomicInteger()
        val failing = object : SectionHydrator {
            override fun supports(type: String, typeVersion: Int): Boolean = type == "decision_card"
            override fun hydrate(context: HydrationContext, section: Section): HydrationResult = error("falha local")
            override val performsIo: Boolean = false
        }
        val coordinator = HydrationCoordinator(
            hydrators = listOf(failing),
            fanOut = Semaphore(8),
            timeout = Duration.ofMillis(80),
            metrics = metrics,
            executor = { runnable -> tasks.incrementAndGet(); Thread.ofVirtual().start(runnable) },
        )
        val result = coordinator.hydrate(
            context = HydrationContext("home", ClientPlatform.IOS, "rev_x", "pt-BR", Channel.STABLE),
            skeleton = skeleton(),
            sections = listOf(
                section("sec_header_1", "header", "top_bar"),
                section("sec_foryou_1", "foryou", "decision_card"),
            ),
            alreadyOmitted = emptyList(),
        )

        assertThat(tasks.get()).`as`("pass-through e hidratador local nao abrem tarefa").isZero()
        assertThat(result.sections.map { it.id }).containsExactly("sec_header_1")
        assertThat(result.omitted.single().reason).isEqualTo(OmittedReason.HYDRATION_FAILED)
        assertThat(metrics.names().count { it == "section.hydrate.ms" }).isEqualTo(2)
    }

    private fun skeleton() = Skeleton(
        skeletonId = MvpCatalog.SKELETON_HOME_DEFAULT,
        revision = 1,
        surface = MvpCatalog.SURFACE_HOME,
        layout = MvpCatalog.SKELETON_LAYOUT,
        slots = listOf(
            SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
            SlotDefinition("foryou", SlotLayout.PAGER, "Para você", 2, listOf("decision_card"), required = false),
        ),
        status = SpecStatus.PUBLISHED,
    )

    private fun section(id: String, slot: String, type: String) = Section(
        id = id,
        slot = slot,
        type = type,
        typeVersion = 1,
        layout = "list",
        props = mapOf("id" to id),
    )
}
