package br.com.empresa.sdui.adapters.mongo

import br.com.empresa.sdui.adapters.json.DomainJson
import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.PublishRequestStatus
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.PageRequest
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.StoreConflict
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import org.bson.Document
import org.bson.conversions.Bson

/*
 * Adapters MongoDB da governança (ADR-021).
 *
 * Cada documento guarda o objeto de domínio serializado em `json` (formato de [DomainJson]) e, ao
 * lado, apenas os campos que alguma consulta filtra ou ordena — são eles que recebem índice. O
 * conteúdo livre das props nunca vira nome de campo no banco: chave de prop com `.` ou `$` não tem
 * como colidir com a sintaxe de consulta, e o formato gravado não depende das regras de nomes do
 * MongoDB.
 */

/**
 * Função utilitária que extrai a string JSON do campo padrão de controle do documento BSON.
 */
private fun Document.json(): String = getString(MongoFields.JSON)

/**
 * Repositório de especificações de telas (specs) sobre o MongoDB (`ADR-021`).
 *
 * ### 1. O que faz
 * Implementa a interface [SpecStore] persistindo e consultando especificações de telas.
 *
 * ### 2. Para que serve
 * Assegurar armazenamento durável de specs com garantia de imutabilidade estrita para revisões
 * já publicadas (`PUBLISHED`) e controle sequencial de revisões por identificador de spec.
 *
 * ### 3. Como funciona
 * Serializa instâncias de [Spec] via [DomainJson] no campo `json` e indexa colunas de filtragem.
 * Operações de escrita condicionam o `_id` (`specId#revision`) a não possuir status `PUBLISHED`,
 * prevenindo que alterações indevidas corrompam o histórico de versões congeladas.
 */
class MongoSpecStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
    private val maxDocumentBytes: Int,
) : SpecStore {
    private val specs = database.getCollection(MongoSchema.SPECS)

    /**
     * Persiste ou atualiza uma especificação no MongoDB.
     *
     * ### 1. O que faz
     * Salva a [spec] no banco de dados através de uma operação de substituição condicional com upsert.
     *
     * ### 2. Para que serve
     * Gravar rascunhos de specs garantindo que versões publicadas jamais sejam sobrescritas.
     *
     * ### 3. Como funciona
     * Executa `replaceOne` condicionando o filtro a `status != PUBLISHED`. Se colidir com chave
     * primária existente em status publicado, captura [MongoWriteException] e lança [StoreConflict].
     */
    override fun save(spec: Spec): Spec {
        val key = "${spec.specId}#${spec.revision}"
        val document = documentFor(spec, key)
        try {
            // Upsert condicionado a nao estar publicada: sobre uma PUBLISHED o filtro nao casa, o
            // upsert tenta inserir o mesmo _id e o indice primario recusa.
            specs.replace(
                sessions,
                Filters.and(Filters.eq(MongoFields.ID, key), Filters.ne("status", SpecStatus.PUBLISHED.name)),
                document,
                upsert = true,
            )
        } catch (error: MongoWriteException) {
            if (error.isDuplicateKey()) {
                throw StoreConflict("spec PUBLISHED e imutavel ou specRevisionId ja usado: $key")
            }
            throw error
        }
        return spec
    }

    /**
     * Atualiza atomicamente uma especificação garantindo que ela não foi alterada desde a leitura.
     *
     * ### 1. O que faz
     * Executa operação de compare-and-set (CAS) para rascunhos de specs.
     *
     * ### 2. Para que serve
     * Prevenir edições simultâneas conflitantes (lost updates) sobre o mesmo rascunho de spec.
     *
     * ### 3. Como funciona
     * Se [expected] for nulo, tenta inserir diretamente. Se informado, valida identidade e garante
     * que a spec não está em status `PUBLISHED`. Realiza `replaceOne` condicionado ao hash/conteúdo
     * JSON exato do rascunho anterior; se zero documentos forem alterados, lança [StoreConflict].
     */
    override fun compareAndSet(expected: Spec?, updated: Spec): Spec {
        val key = "${updated.specId}#${updated.revision}"
        if (expected != null && (expected.specId != updated.specId || expected.revision != updated.revision || expected.status == SpecStatus.PUBLISHED)) {
            throw StoreConflict("revisao PUBLISHED e imutavel ou identidade divergente")
        }
        val document = documentFor(updated, key)
        try {
            if (expected == null) {
                specs.insert(sessions, document)
            } else {
                val changed = specs.replace(
                    sessions,
                    Filters.and(
                        Filters.eq(MongoFields.ID, key),
                        Filters.eq(MongoFields.JSON, DomainJson.write(expected))
                    ),
                    document,
                    upsert = false,
                )
                if (changed.matchedCount == 0L) throw StoreConflict("rascunho mudou desde a leitura")
            }
        } catch (error: MongoWriteException) {
            if (error.isDuplicateKey()) throw StoreConflict("identidade de revisao ja usada")
            throw error
        }
        return updated
    }

    private fun documentFor(updated: Spec, key: String): Document {
        return Document(MongoFields.ID, key)
            .append("specId", updated.specId)
            .append("revision", updated.revision)
            .append("specRevisionId", updated.specRevisionId)
            .append("surface", updated.surface)
            .append("platform", updated.platform.name)
            .append("channel", updated.channel.name)
            .append("status", updated.status.name)
            .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
            .append(MongoFields.JSON, boundedPayload(DomainJson.write(updated), maxDocumentBytes, "spec $key"))
    }

    /**
     * Localiza uma especificação pelo seu identificador único global de revisão.
     *
     * ### 1. O que faz
     * Busca um documento de spec onde `specRevisionId` seja exatamente igual ao fornecido.
     *
     * ### 2. Para que serve
     * Recuperar a especificação imutável referenciada por ponteiros de publicação e requisições.
     *
     * ### 3. Como funciona
     * Consulta o índice único `ux_spec_revision` e desserializa o JSON correspondente via [DomainJson].
     */
    override fun findByRevisionId(specRevisionId: String): Spec? =
        specs.firstMatch(sessions, Filters.eq("specRevisionId", specRevisionId))?.toSpec()

    /**
     * Localiza uma especificação pelo par lógico de identificador de spec e número de revisão.
     *
     * ### 1. O que faz
     * Recupera uma revisão pontual de uma spec através de sua chave primária composta.
     *
     * ### 2. Para que serve
     * Permitir a navegação e inspeção de versões específicas de telas no painel administrativo.
     *
     * ### 3. Como funciona
     * Efetua busca pontual por chave primária [MongoFields.ID] (`specId#revision`).
     */
    override fun findBySpecIdAndRevision(specId: String, revision: Int): Spec? =
        specs.firstMatch(sessions, Filters.eq(MongoFields.ID, "$specId#$revision"))?.toSpec()

    /**
     * Lista todas as revisões de uma especificação ordenadas pelo número de revisão.
     *
     * ### 1. O que faz
     * Retorna a lista completa de revisões de um dado `specId`.
     *
     * ### 2. Para que serve
     * Apresentar o histórico cronológico de versões e rascunhos de uma tela.
     *
     * ### 3. Como funciona
     * Filtra por `specId` aplicando ordenação ascendente no campo `revision`.
     */
    override fun listBySpecId(specId: String): List<Spec> =
        specs.allMatching(sessions, Filters.eq("specId", specId), Sorts.ascending("revision")).map { it.toSpec() }

    /**
     * Lista as revisões de uma especificação de forma paginada.
     *
     * ### 1. O que faz
     * Recupera uma página de revisões para um dado `specId`.
     *
     * ### 2. Para que serve
     * Viabilizar paginação em telas de listagem administrativa de governança.
     *
     * ### 3. Como funciona
     * Repassa os parâmetros de `offset` e `limit` contidos em [page] para a consulta no MongoDB.
     */
    override fun listBySpecId(specId: String, page: PageRequest): List<Spec> =
        specs.allMatching(
            sessions,
            Filters.eq("specId", specId),
            Sorts.ascending("revision"),
            skip = page.offset,
            limit = page.limit,
        ).map { it.toSpec() }

    /**
     * Lista todas as especificações publicadas para uma superfície e plataforma.
     *
     * ### 1. O que faz
     * Recupera as specs com status `PUBLISHED` vinculadas à superfície e plataforma informadas.
     *
     * ### 2. Para que serve
     * Fornecer a base de specs elegíveis para o algoritmo de targeting no pipeline de composição.
     *
     * ### 3. Como funciona
     * Utiliza o índice `ix_spec_published` combinando `surface`, `platform` e `status == PUBLISHED`.
     */
    override fun listPublished(surface: String, platform: ClientPlatform): List<Spec> =
        specs.allMatching(
            sessions,
            Filters.and(
                Filters.eq("surface", surface),
                Filters.eq("platform", platform.name),
                Filters.eq("status", SpecStatus.PUBLISHED.name),
            ),
        ).map { it.toSpec() }

    /**
     * Lista especificações filtradas opcionalmente por plataforma e canal.
     *
     * ### 1. O que faz
     * Devolve a relação de specs cadastradas conforme os filtros aplicados.
     *
     * ### 2. Para que serve
     * Permitir filtragem ampla de especificações no console administrativo.
     *
     * ### 3. Como funciona
     * Compõe dinamicamente filtros BSON para [platform] e [channel], ordenando por `specId` e `revision`.
     */
    override fun list(platform: ClientPlatform?, channel: Channel?): List<Spec> =
        specs.allMatching(sessions, filterOf(platform, channel), Sorts.ascending("specId", "revision"))
            .map { it.toSpec() }

    /**
     * Lista especificações filtradas por plataforma e canal de maneira paginada.
     *
     * ### 1. O que faz
     * Consulta specs com filtros opcionais aplicando restrições de paginação.
     *
     * ### 2. Para que serve
     * Suportar paginação eficiente no painel de administração de telas.
     *
     * ### 3. Como funciona
     * Combina o filtro opcional com `skip` e `limit` de [page].
     */
    override fun list(platform: ClientPlatform?, channel: Channel?, page: PageRequest): List<Spec> =
        specs.allMatching(
            sessions,
            filterOf(platform, channel),
            Sorts.ascending("specId", "revision"),
            skip = page.offset,
            limit = page.limit,
        ).map { it.toSpec() }

    /**
     * Determina o próximo número de revisão sequencial para um identificador de spec.
     *
     * ### 1. O que faz
     * Calcula o próximo ordinal inteiro incremental de revisão para a [specId].
     *
     * ### 2. Para que serve
     * Garantir sequência estrita de revisões sem lacunas ao iniciar novos rascunhos.
     *
     * ### 3. Como funciona
     * Localiza a última revisão cadastrada ordenando descendentemente e soma 1. Se estourar `Int.MAX_VALUE`,
     * lança [StoreConflict].
     */
    override fun nextRevision(specId: String): Int {
        val last = specs.firstMatch(sessions, Filters.eq("specId", specId), Sorts.descending("revision"))
            ?.getInteger("revision") ?: 0
        if (last == Int.MAX_VALUE) throw StoreConflict("limite de revisoes atingido")
        return last + 1
    }

    private fun filterOf(platform: ClientPlatform?, channel: Channel?): Bson {
        val filters = listOfNotNull(
            platform?.let { Filters.eq("platform", it.name) },
            channel?.let { Filters.eq("channel", it.name) },
        )
        return if (filters.isEmpty()) Filters.empty() else Filters.and(filters)
    }

    private fun Document.toSpec(): Spec = DomainJson.read(json(), Spec::class.java)
}

/**
 * Repositório de skeletons de superfícies sobre o MongoDB (`ADR-021`).
 *
 * ### 1. O que faz
 * Implementa a interface [SkeletonStore] persistindo e consultando layouts e slots de superfícies.
 *
 * ### 2. Para que serve
 * Assegurar armazenamento durável de esqueletos de telas, garantindo imutabilidade de revisões
 * publicadas (`PUBLISHED`) e integridade referencial nas composições de telas.
 *
 * ### 3. Como funciona
 * Serializa instâncias de [Skeleton] em JSON via [DomainJson], indexando `skeletonId` e `revision`.
 * As gravações impedem a substituição de documentos já publicados, preservando o layout contratado.
 */
class MongoSkeletonStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
    private val maxDocumentBytes: Int,
) : SkeletonStore {
    private val skeletons = database.getCollection(MongoSchema.SKELETONS)

    /**
     * Salva ou atualiza um skeleton no MongoDB.
     *
     * ### 1. O que faz
     * Persiste o [skeleton] na coleção `skeletons` através de substituição condicional.
     *
     * ### 2. Para que serve
     * Gravar rascunhos de skeletons protegendo revisões publicadas contra sobrescrita acidental.
     *
     * ### 3. Como funciona
     * Aplica `replaceOne` condicionado a `status != PUBLISHED`. Se colidir com chave primária já
     * publicada, intercepta [MongoWriteException] e lança [StoreConflict].
     */
    override fun save(skeleton: Skeleton): Skeleton {
        val key = "${skeleton.skeletonId}#${skeleton.revision}"
        val document = documentFor(skeleton, key)
        try {
            skeletons.replace(
                sessions,
                Filters.and(Filters.eq(MongoFields.ID, key), Filters.ne("status", SpecStatus.PUBLISHED.name)),
                document,
                upsert = true,
            )
        } catch (error: MongoWriteException) {
            if (error.isDuplicateKey()) throw StoreConflict("skeleton PUBLISHED e imutavel: $key")
            throw error
        }
        return skeleton
    }

    /**
     * Atualiza atomicamente um skeleton garantindo que o rascunho não foi modificado concorrentemente.
     *
     * ### 1. O que faz
     * Executa operação de compare-and-set (CAS) sobre rascunhos de skeletons.
     *
     * ### 2. Para que serve
     * Impedir sobrescrita concorrente cega entre operadores durante a definição de slots de telas.
     *
     * ### 3. Como funciona
     * Compara o JSON serializado de [expected] com o registro no banco; caso haja divergência ou
     * status publicado, lança [StoreConflict].
     */
    override fun compareAndSet(expected: Skeleton?, updated: Skeleton): Skeleton {
        val key = "${updated.skeletonId}#${updated.revision}"
        if (expected != null && (expected.skeletonId != updated.skeletonId || expected.revision != updated.revision || expected.status == SpecStatus.PUBLISHED)) {
            throw StoreConflict("revisao PUBLISHED e imutavel ou identidade divergente")
        }
        val document = documentFor(updated, key)
        try {
            if (expected == null) {
                skeletons.insert(sessions, document)
            } else {
                val changed = skeletons.replace(
                    sessions,
                    Filters.and(
                        Filters.eq(MongoFields.ID, key),
                        Filters.eq(MongoFields.JSON, DomainJson.write(expected))
                    ),
                    document,
                    upsert = false,
                )
                if (changed.matchedCount == 0L) throw StoreConflict("rascunho mudou desde a leitura")
            }
        } catch (error: MongoWriteException) {
            if (error.isDuplicateKey()) throw StoreConflict("identidade de revisao ja usada")
            throw error
        }
        return updated
    }

    private fun documentFor(updated: Skeleton, key: String): Document {
        return Document(MongoFields.ID, key)
            .append("skeletonId", updated.skeletonId)
            .append("revision", updated.revision)
            .append("surface", updated.surface)
            .append("status", updated.status.name)
            .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
            .append(MongoFields.JSON, boundedPayload(DomainJson.write(updated), maxDocumentBytes, "skeleton $key"))
    }

    /**
     * Localiza um skeleton pelo identificador e número de revisão opcional.
     *
     * ### 1. O que faz
     * Recupera um skeleton pontual ou o mais recente caso a revisão seja omitida.
     *
     * ### 2. Para que serve
     * Resolver a estrutura de slots requerida pela spec durante a composição da tela.
     *
     * ### 3. Como funciona
     * Se [revision] for nula, delega para [current]. Caso contrário, pesquisa pelo `_id` composto.
     */
    override fun find(skeletonId: String, revision: Int?): Skeleton? {
        if (revision == null) return current(skeletonId)
        return skeletons.firstMatch(sessions, Filters.eq(MongoFields.ID, "$skeletonId#$revision"))?.toSkeleton()
    }

    /**
     * Localiza a revisão mais recente do skeleton especificado.
     *
     * ### 1. O que faz
     * Retorna a versão de maior número ordinal de revisão para o [skeletonId].
     *
     * ### 2. Para que serve
     * Obter a versão corrente padrão da estrutura da tela.
     *
     * ### 3. Como funciona
     * Filtra por `skeletonId` ordenando descendentemente por `revision` e seleciona o primeiro.
     */
    override fun current(skeletonId: String): Skeleton? =
        skeletons.firstMatch(sessions, Filters.eq("skeletonId", skeletonId), Sorts.descending("revision"))
            ?.toSkeleton()

    private fun Document.toSkeleton(): Skeleton = DomainJson.read(json(), Skeleton::class.java)
}

/**
 * Repositório do catálogo de componentes sobre o MongoDB (`ADR-021`).
 *
 * ### 1. O que faz
 * Implementa a interface [CatalogStore] persistindo e consultando o catálogo global de componentes.
 *
 * ### 2. Para que serve
 * Manter o cadastro centralizado de componentes homologados, versões ativas e propriedades obrigatórias.
 *
 * ### 3. Como funciona
 * Armazena um único documento de identificador fixo (`current`). A gravação substitui o documento
 * integralmente com validação prévia de tamanho via [boundedPayload].
 */
class MongoCatalogStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
    private val maxDocumentBytes: Int,
) : CatalogStore {
    private val catalog = database.getCollection(MongoSchema.CATALOG)

    /**
     * Salva o catálogo completo de componentes no MongoDB.
     *
     * ### 1. O que faz
     * Substitui o documento único do catálogo pela nova instância fornecida.
     *
     * ### 2. Para que serve
     * Atualizar a lista de componentes e capabilities conhecidas do ecossistema SDUI.
     *
     * ### 3. Como funciona
     * Executa `replaceOne` com `upsert = true` sobre o identificador fixo `current`.
     */
    override fun save(catalog: Catalog): Catalog {
        val document = Document(MongoFields.ID, CURRENT)
            .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
            .append(MongoFields.JSON, boundedPayload(DomainJson.write(catalog), maxDocumentBytes, "catalogo"))
        this.catalog.replace(sessions, Filters.eq(MongoFields.ID, CURRENT), document, upsert = true)
        return catalog
    }

    /**
     * Recupera o catálogo corrente de componentes.
     *
     * ### 1. O que faz
     * Busca o documento atual do catálogo no MongoDB.
     *
     * ### 2. Para que serve
     * Fornecer ao orquestrador a matriz de capabilities ativas para validações de contratos e targeting.
     *
     * ### 3. Como funciona
     * Consulta o documento pelo id `current` e desserializa via [DomainJson]; se ausente, retorna [Catalog] vazio.
     */
    override fun current(): Catalog =
        catalog.firstMatch(sessions, Filters.eq(MongoFields.ID, CURRENT))
            ?.let { DomainJson.read(it.json(), Catalog::class.java) }
            ?: Catalog(emptyList())

    private companion object {
        const val CURRENT: String = "current"
    }
}

/**
 * Repositório de ponteiros de publicação sobre o MongoDB (`ADR-021`).
 *
 * ### 1. O que faz
 * Implementa a interface [PointerStore] gerenciando ponteiros ativos de publicação por superfície, plataforma e canal.
 *
 * ### 2. Para que serve
 * Controlar atômica e monotonicamente qual revisão de spec está ativa no momento da composição.
 *
 * ### 3. Como funciona
 * Cada ponteiro possui controle de concorrência otimista (OCC) baseado no campo `version`. Gravações
 * concorrentes que encontrem versão divergente da esperada disparam [StoreConflict].
 */
class MongoPointerStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
) : PointerStore {
    private val pointers = database.getCollection(MongoSchema.POINTERS)

    /**
     * Localiza o ponteiro ativo para a superfície, plataforma e canal especificados.
     *
     * ### 1. O que faz
     * Recupera o registro de ponteiro correspondente à tripla informada.
     *
     * ### 2. Para que serve
     * Identificar a spec ativa para direcionar a montagem da tela no hot path de composição.
     *
     * ### 3. Como funciona
     * Busca na coleção de ponteiros pela chave primária formatada `surface:platform:channel`.
     */
    override fun find(surface: String, platform: ClientPlatform, channel: Channel): Pointer? =
        pointers.firstMatch(sessions, Filters.eq(MongoFields.ID, key(surface, platform, channel)))
            ?.let { DomainJson.read(it.json(), Pointer::class.java) }

    /**
     * Salva ou substitui incondicionalmente um ponteiro de publicação.
     *
     * ### 1. O que faz
     * Grava o [pointer] no MongoDB com upsert.
     *
     * ### 2. Para que serve
     * Inicializar ponteiros no bootstrap ou executar reconciliações administrativas.
     *
     * ### 3. Como funciona
     * Executa `replaceOne` com `upsert = true` chaveado pela tripla da superfície.
     */
    override fun save(pointer: Pointer): Pointer {
        pointers.replace(sessions, Filters.eq(MongoFields.ID, key(pointer)), document(pointer), upsert = true)
        return pointer
    }

    /**
     * Atualiza o ponteiro de publicação de forma atômica se a versão coincidir com a esperada.
     *
     * ### 1. O que faz
     * Executa compare-and-set (CAS) sobre o ponteiro verificando o número de versão.
     *
     * ### 2. Para que serve
     * Evitar condições de corrida em publicações e rollbacks simultâneos entre múltiplos pods.
     *
     * ### 3. Como funciona
     * Se [expectedVersion] for nulo, tenta inserir novo documento; se já existir, lança [StoreConflict].
     * Se informada, executa `replaceOne` condicionado a `version == expectedVersion`. Se nenhum documento
     * for atualizado, lança [StoreConflict].
     */
    override fun compareAndSet(expectedVersion: Long?, updated: Pointer): Pointer {
        val key = key(updated)
        if (expectedVersion == null) {
            try {
                pointers.insert(sessions, document(updated))
            } catch (error: MongoWriteException) {
                if (error.isDuplicateKey()) throw StoreConflict("pointer $key ja existe")
                throw error
            }
            return updated
        }
        val result = pointers.replace(
            sessions,
            Filters.and(Filters.eq(MongoFields.ID, key), Filters.eq("version", expectedVersion)),
            document(updated),
            upsert = false,
        )
        if (result.matchedCount == 0L) {
            throw StoreConflict("pointer $key mudou desde a leitura (esperado v$expectedVersion)")
        }
        return updated
    }

    private fun document(pointer: Pointer): Document = Document(MongoFields.ID, key(pointer))
        .append("surface", pointer.surface)
        .append("platform", pointer.platform.name)
        .append("channel", pointer.channel.name)
        .append("version", pointer.version)
        .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
        .append(MongoFields.JSON, DomainJson.write(pointer))

    private fun key(pointer: Pointer): String = key(pointer.surface, pointer.platform, pointer.channel)

    private fun key(surface: String, platform: ClientPlatform, channel: Channel): String =
        "$surface:${platform.wire()}:${channel.wire()}"
}

/**
 * Repositório de pedidos de publicação sobre o MongoDB (`ADR-021`).
 *
 * ### 1. O que faz
 * Implementa a interface [PublishRequestStore] armazenando requisições do fluxo maker-checker.
 *
 * ### 2. Para que serve
 * Rastrear a abertura, aprovação ou rejeição de pedidos formais de publicação de telas.
 *
 * ### 3. Como funciona
 * Armazena os pedidos na coleção `publish_requests`. Transições de estado são controladas via
 * `findOneAndReplace` condicionado ao status anterior esperado, garantindo linearidade de transição.
 */
class MongoPublishRequestStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
) : PublishRequestStore {
    private val requests = database.getCollection(MongoSchema.PUBLISH_REQUESTS)

    /**
     * Salva ou atualiza um pedido de publicação no MongoDB.
     *
     * ### 1. O que faz
     * Grava o [request] utilizando seu identificador como chave primária.
     *
     * ### 2. Para que serve
     * Registrar novos pedidos de publicação ou atualizar metadados associados.
     *
     * ### 3. Como funciona
     * Executa `replaceOne` com `upsert = true` baseado em `requestId`.
     */
    override fun save(request: PublishRequest): PublishRequest {
        requests.replace(sessions, Filters.eq(MongoFields.ID, request.requestId), document(request), upsert = true)
        return request
    }

    /**
     * Localiza um pedido de publicação pelo identificador da requisição.
     *
     * ### 1. O que faz
     * Busca o pedido de publicação correspondente ao [requestId].
     *
     * ### 2. Para que serve
     * Consultar os detalhes de um pedido pendente ou finalizado para tomada de decisão.
     *
     * ### 3. Como funciona
     * Efetua busca pontual por chave primária [MongoFields.ID] e desserializa via [DomainJson].
     */
    override fun find(requestId: String): PublishRequest? =
        requests.firstMatch(sessions, Filters.eq(MongoFields.ID, requestId))
            ?.let { DomainJson.read(it.json(), PublishRequest::class.java) }

    /**
     * Atualiza atomicamente o status de um pedido de publicação se estiver no status esperado.
     *
     * ### 1. O que faz
     * Transiciona o status de um pedido de publicação via compare-and-set atômico no banco.
     *
     * ### 2. Para que serve
     * Evitar decisões concorrentes simultâneas (ex.: duas aprovações ou aprovação e rejeição ao mesmo tempo).
     *
     * ### 3. Como funciona
     * Executa `findOneAndReplace` filtrando por `requestId` e `status == expected.name`. Retorna o
     * objeto atualizado se a transição foi bem-sucedida, ou `null` caso contrário.
     */
    override fun compareAndSetStatus(
        requestId: String,
        expected: PublishRequestStatus,
        updated: PublishRequest,
    ): PublishRequest? {
        val replaced = requests.findAndReplace(
            sessions,
            Filters.and(Filters.eq(MongoFields.ID, requestId), Filters.eq("status", expected.name)),
            document(updated),
        )
        return replaced?.let { updated }
    }

    private fun document(request: PublishRequest): Document = Document(MongoFields.ID, request.requestId)
        .append("status", request.status.name)
        .append("specRevisionId", request.specRevisionId)
        .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
        .append(MongoFields.JSON, DomainJson.write(request))
}

/**
 * Repositório de diffs pré-calculados entre revisões de especificações (`ADR-021`).
 *
 * ### 1. O que faz
 * Implementa a interface [DiffStore] persistindo representações de diferenças semânticas entre specs.
 *
 * ### 2. Para que serve
 * Disponibilizar diffs estruturais calculados na abertura do pedido para consulta imediata sem reprocessamento.
 *
 * ### 3. Como funciona
 * Identifica cada registro pela chave composta `specId:fromRevision:toRevision` e valida o tamanho
 * do payload antes de persistir no MongoDB.
 */
class MongoDiffStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
    private val maxDocumentBytes: Int,
) : DiffStore {
    private val diffs = database.getCollection(MongoSchema.DIFFS)

    /**
     * Salva o diff calculado entre revisões de uma especificação.
     *
     * ### 1. O que faz
     * Grava a entidade [SpecDiff] na coleção `diffs` com substituição condicional.
     *
     * ### 2. Para que serve
     * Guardar o resultado da comparação estrutural entre a revisão atual e a proposta.
     *
     * ### 3. Como funciona
     * Gera chave `specId:from:to` e grava o documento BSON aplicando validação de tamanho máximo.
     */
    override fun save(diff: SpecDiff): SpecDiff {
        val key = key(diff.specId, diff.fromRevision ?: 0, diff.toRevision)
        val document = Document(MongoFields.ID, key)
            .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
            .append(MongoFields.JSON, boundedPayload(DomainJson.write(diff), maxDocumentBytes, "diff $key"))
        diffs.replace(sessions, Filters.eq(MongoFields.ID, key), document, upsert = true)
        return diff
    }

    /**
     * Localiza um diff pré-calculado entre duas revisões de uma especificação.
     *
     * ### 1. O que faz
     * Recupera o diff correspondente ao par de revisões [from] e [to].
     *
     * ### 2. Para que serve
     * Exibir as alterações propostas na interface ou API de revisão de governança.
     *
     * ### 3. Como funciona
     * Consulta pontualmente pelo identificador primário `specId:from:to`.
     */
    override fun find(specId: String, from: Int, to: Int): SpecDiff? =
        diffs.firstMatch(sessions, Filters.eq(MongoFields.ID, key(specId, from, to)))
            ?.let { DomainJson.read(it.json(), SpecDiff::class.java) }

    private fun key(specId: String, from: Int, to: Int): String = "$specId:$from:$to"
}

/**
 * Trilha de auditoria append-only sobre o MongoDB (`ADR-021`).
 *
 * ### 1. O que faz
 * Implementa a interface [AuditLogStore] registrando eventos imutáveis de ações de governança.
 *
 * ### 2. Para que serve
 * Fornecer rastreabilidade integral para auditorias de segurança, conformidade e governança.
 *
 * ### 3. Como funciona
 * Suporta exclusivamente inserções (`insertOne`). Consultas aplicam ordenação cronológica decrescente
 * pelo timestamp em milissegundos (`tsMillis`). Não aplica podas automáticas por pressão de memória.
 */
class MongoAuditLogStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
) : AuditLogStore {
    private val events = database.getCollection(MongoSchema.AUDIT_EVENTS)

    /**
     * Adiciona um novo evento de auditoria à trilha imutável.
     *
     * ### 1. O que faz
     * Insere um registro [AuditEvent] na coleção `audit_events`.
     *
     * ### 2. Para que serve
     * Registrar ações administrativas como publicações, aprovações, rejeições e rollbacks.
     *
     * ### 3. Como funciona
     * Constrói o documento com metadados de tempo, ator e ação, inserindo-o na sessão ativa.
     */
    override fun append(event: AuditEvent) {
        events.insert(
            sessions,
            Document(MongoFields.ID, event.id)
                .append("tsMillis", event.ts.toEpochMilli())
                .append("action", event.action)
                .append("surface", event.surface)
                .append("platform", event.platform.name)
                .append("channel", event.channel.name)
                .append("requestId", event.requestId)
                .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
                .append(MongoFields.JSON, DomainJson.write(event)),
        )
    }

    /**
     * Lista os eventos de auditoria mais recentes até o limite máximo de página em ordem cronológica.
     *
     * ### 1. O que faz
     * Recupera os eventos recentes e os ordena cronologicamente para exibição.
     *
     * ### 2. Para que serve
     * Alimentar relatórios e consoles administrativos de governança.
     *
     * ### 3. Como funciona
     * Invoca [recent] com [PageRequest.MAX_LIMIT] e reverte a lista para ordem ascendente.
     */
    override fun list(): List<AuditEvent> = recent(PageRequest.MAX_LIMIT).asReversed()

    /**
     * Retorna os eventos de auditoria mais recentes até o limite especificado.
     *
     * ### 1. O que faz
     * Consulta os últimos [limit] eventos ordenados do mais recente para o mais antigo.
     *
     * ### 2. Para que serve
     * Permitir inspeção rápida dos últimos acontecimentos de governança no sistema.
     *
     * ### 3. Como funciona
     * Exige `limit >= 0`. Se zero, retorna lista vazia. Caso contrário, busca na coleção ordenando
     * por `tsMillis` descendente e limita os resultados ao teto estipulado.
     */
    override fun recent(limit: Int): List<AuditEvent> {
        // No driver, limit 0 quer dizer sem limite: sem este atalho a colecao inteira viria para o
        // heap. Aqui quer dizer nenhum, como no adapter em memoria.
        require(limit >= 0) { "limit deve ser >= 0" }
        if (limit == 0) return emptyList()
        return events.allMatching(sessions, Filters.empty(), Sorts.descending("tsMillis"), limit = limit)
            .map { DomainJson.read(it.json(), AuditEvent::class.java) }
    }
}
