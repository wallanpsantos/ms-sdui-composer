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
 * Carregador de telas demonstrativas via fluxo administrativo real de governança (`T10`).
 *
 * ### 1. O que faz
 * Lê os exemplos de telas de `demo/screens` no classpath e os publica através dos casos de uso reais
 * de rascunho ([DraftUseCase]) e publicação maker-checker ([PublishUseCase]).
 *
 * ### 2. Para que serve
 * Disponibilizar telas demonstrativas ricas para testes de integração, homologação móvel e ensaios
 * operacionais sem burlar as validações de governança nem escrever diretamente nos repositórios.
 *
 * ### 3. Como funciona
 * Ativado quando a propriedade `sdui.demo-enabled=true` está configurada. Lê os recursos JSON do classpath
 * utilizando encerramento seguro de stream via `.use { }` para evitar vazamento de file descriptors.
 * Cria rascunhos de skeleton, inclui contratos de componentes aprovados no catálogo como ativos,
 * cria rascunho da spec, abre pedido de publicação com ator sintético `demo.maker` e aprova com `demo.checker`.
 * As chaves de idempotência determinísticas previnem duplicações em reinicializações.
 */
class DemoScreensLoader(
    private val drafts: DraftUseCase,
    private val publish: PublishUseCase,
    private val specStore: SpecStore,
    private val skeletonStore: SkeletonStore,
    private val catalogStore: CatalogStore,
    private val resource: (String) -> String? = ::classpathText,
) {
    /**
     * Resultado do processamento de um exemplo demonstrativo.
     *
     * ### 1. O que faz
     * Informa se a tela demonstrativa [example] foi publicada nesta execução ou se já estava ativa.
     *
     * ### 2. Para que serve
     * Fornecer telemetria e rastreamento da carga para logs e testes de inicialização.
     *
     * ### 3. Como funciona
     * Contém o nome do [example], o identificador [specRevisionId] e a flag booleana [published].
     */
    data class Outcome(val example: String, val specRevisionId: String, val published: Boolean)

    /**
     * Carrega e publica todos os exemplos demonstrativos cadastrados na ordem definida por [EXAMPLES].
     *
     * ### 1. O que faz
     * Itera sobre a lista [EXAMPLES] executando a carga e publicação de cada exemplo.
     *
     * ### 2. Para que serve
     * Inicializar o ambiente demonstrativo completo em lote com ponteiros apontando para as versões esperadas.
     *
     * ### 3. Como funciona
     * Invoca [load] para cada item da lista e devolve a relação consolidada de [Outcome].
     */
    fun loadAll(): List<Outcome> = EXAMPLES.map { load(it) }

    /**
     * Carrega e publica uma tela demonstrativa específica pelo fluxo administrativo.
     *
     * ### 1. O que faz
     * Processa os arquivos `skeleton.json` e `spec.json` do exemplo informado.
     *
     * ### 2. Para que serve
     * Publicar a tela demonstrativa submetendo-a a todas as validações de schema, contratos e imutabilidade.
     *
     * ### 3. Como funciona
     * Lê os JSONs com [read]. Se a spec já estiver em status `PUBLISHED` no [specStore], retorna `Outcome` com
     * `published = false`. Caso contrário, cria o skeleton se inexistente, garante os contratos no catálogo
     * via [ensureContracts], cria o rascunho da spec, abre o pedido com [publish.open] e o aprova com [publish.approve].
     */
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

    /**
     * Garante que os contratos de componentes referenciados pelo exemplo estejam cadastrados no catálogo.
     *
     * ### 1. O que faz
     * Adiciona capabilities aprovadas em [ComponentContracts.APPROVED] como ativas no catálogo caso ainda não existam.
     *
     * ### 2. Para que serve
     * Prevenir falhas de validação de catálogo na criação do rascunho da spec de demonstração.
     *
     * ### 3. Como funciona
     * Compara as capabilities das seções da spec com as presentes no catálogo e inclui as pendentes via [DraftUseCase.upsertComponent].
     */
    private fun ensureContracts(spec: Spec) {
        val present = catalogStore.current().components.map { it.capability() }.toSet()
        spec.sections.map { it.capability }
            .filter { it in ComponentContracts.APPROVED && it !in present }
            .distinct()
            .forEach { capability ->
                drafts.upsertComponent(
                    DraftCatalogCommand(
                        MAKER,
                        ComponentType(
                            capability.type,
                            capability.typeVersion,
                            ComponentType.STATUS_ACTIVE,
                            MvpCatalog.SCHEMA_VERSION,
                            emptyList(),
                        ),
                    ),
                )
            }
    }

    private fun <T : Any> read(path: String, type: Class<T>): T {
        val json = resource(path) ?: error("exemplo ausente no classpath: $path")
        return DomainJson.read(json, type)
    }

    companion object {
        /** Diretório raiz dos recursos de demonstração dentro do classpath. */
        const val ROOT: String = "demo/screens"

        /**
         * Relação ordenada de exemplos a carregar.
         *
         * A sequência garante que no Android `banking.cards_first` seja publicado por último,
         * ficando como revisão ativa no ponteiro e `banking.shortcuts_first` como revisão anterior para testes de rollback.
         */
        val EXAMPLES: List<String> = listOf(
            "banking.shortcuts_first",
            "banking.cards_first",
            "banking.transactions",
            "fashion.catalog",
        )

        private val MAKER = Actor("demo.maker", ActorRole.MAKER)
        private val CHECKER = Actor("demo.checker", ActorRole.CHECKER)

        /**
         * Lê um arquivo de recurso do classpath utilizando fechamento garantido do stream via [.use].
         *
         * Evita vazamento de descritores de arquivo (file descriptors) na leitura de arquivos JAR/classes.
         */
        private fun classpathText(path: String): String? =
            DemoScreensLoader::class.java.classLoader.getResourceAsStream(path)
                ?.use { it.readBytes().decodeToString() }
    }
}
