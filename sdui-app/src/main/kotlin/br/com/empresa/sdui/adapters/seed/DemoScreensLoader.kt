package br.com.empresa.sdui.adapters.seed

import br.com.empresa.sdui.adapters.json.DomainJson
import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.core.model.ComponentContracts
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.port.inbound.DecidePublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftCatalogCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftSkeletonCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftSpecCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.OpenPublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.PublishUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore

/**
 * Publica os quatro exemplos de `docs/examples/screens` pelo fluxo administrativo real (T10).
 *
 * Nada aqui escreve direto em store: o skeleton vira rascunho, os contratos novos entram no
 * catalogo, o spec vira rascunho validado, o maker abre o pedido e o checker aprova. Um exemplo
 * invalido falha na subida do modo demo com os mesmos erros que o operador veria na API. Os
 * atores sao sinteticos (`demo.maker` e `demo.checker`) e as chaves de idempotencia sao fixas por
 * exemplo, entao reexecutar a carga — ou subir de novo com persistencia — nao duplica nada.
 *
 * So roda com `sdui.demo-enabled=true`. O conteudo e sintetico e de locale fixo pt-BR.
 */
class DemoScreensLoader(
    private val drafts: DraftUseCase,
    private val publish: PublishUseCase,
    private val specStore: SpecStore,
    private val skeletonStore: SkeletonStore,
    private val catalogStore: CatalogStore,
    private val resource: (String) -> String? = ::classpathText,
) {
    /** O que aconteceu com um exemplo: publicado agora ou ja publicado antes. */
    data class Outcome(val example: String, val specRevisionId: String, val published: Boolean)

    /** Carrega todos, na ordem de [EXAMPLES]; a ordem define o pointer final de cada plataforma. */
    fun loadAll(): List<Outcome> = EXAMPLES.map { load(it) }

    fun load(example: String): Outcome {
        val skeleton = read("$ROOT/$example/skeleton.json", Skeleton::class.java)
        val spec = read("$ROOT/$example/spec.json", Spec::class.java)
        specStore.findByRevisionId(spec.specRevisionId)
            ?.takeIf { it.status == SpecStatus.PUBLISHED }
            ?.let { return Outcome(example, it.specRevisionId, published = false) }

        if (skeletonStore.find(skeleton.skeletonId, skeleton.revision) == null) {
            drafts.createSkeletonDraft(DraftSkeletonCommand(MAKER, skeleton))
        }
        ensureContracts(spec)
        val draft = drafts.createSpecDraft(DraftSpecCommand(MAKER, spec))
        val request = publish.open(
            OpenPublishCommand(MAKER, draft.specId, draft.revision, draft.channel, "demo:$example:open"),
        )
        publish.approve(DecidePublishCommand(CHECKER, request.requestId, "demo:$example:approve"))
        return Outcome(example, draft.specRevisionId, published = true)
    }

    /** Contratos novos usados pelo exemplo entram no catalogo como ACTIVE, pela governanca. */
    private fun ensureContracts(spec: Spec) {
        val present = catalogStore.current().components.map { it.capability() }.toSet()
        spec.sections.map { it.capability }
            .filter { it in ComponentContracts.APPROVED && it !in present }
            .distinct()
            .forEach { capability ->
                drafts.upsertComponent(
                    DraftCatalogCommand(
                        MAKER,
                        ComponentType(capability.type, capability.typeVersion, "ACTIVE", MvpCatalog.SCHEMA_VERSION, emptyList()),
                    ),
                )
            }
    }

    private fun <T : Any> read(path: String, type: Class<T>): T {
        val json = resource(path) ?: error("exemplo ausente no classpath: $path")
        return DomainJson.read(json, type)
    }

    companion object {
        const val ROOT: String = "demo/screens"

        /**
         * Ordem de carga. As duas montagens Android da mesma surface terminam com cards-first no
         * pointer e shortcuts-first como revisao anterior — o rollback alterna entre elas.
         */
        val EXAMPLES: List<String> = listOf(
            "banking.shortcuts_first",
            "banking.cards_first",
            "banking.transactions",
            "fashion.catalog",
        )

        private val MAKER = Actor("demo.maker", ActorRole.MAKER)
        private val CHECKER = Actor("demo.checker", ActorRole.CHECKER)

        private fun classpathText(path: String): String? =
            DemoScreensLoader::class.java.classLoader.getResourceAsStream(path)
                ?.use { it.readBytes().decodeToString() }
    }
}
