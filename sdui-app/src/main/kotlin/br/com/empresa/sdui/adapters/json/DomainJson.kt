package br.com.empresa.sdui.adapters.json

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import com.fasterxml.jackson.annotation.JsonIgnore
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * Provedor de serializacao e desserializacao JSON para o modelo de dominio (`ADR-021`).
 *
 * ### 1. O que faz
 * Configura e disponibiliza uma instancia isolada de [JsonMapper] do Jackson 3 com modulos
 * e MixIns dedicados para persistencia e cache de entidades do dominio (`Section`, `Skeleton`, `SemVer`).
 *
 * ### 2. Para que serve
 * Isola a camada de dominio pura de anotacoes do framework Jackson e protege o formato dos dados
 * persistidos no MongoDB e Redis de alteracoes acidentais provocadas por mudancas nas configuracoes
 * padrao do Spring Boot ou do Spring MVC.
 *
 * ### 3. Como funciona
 * Instancia o [JsonMapper] desabilitando falhas por propriedades desconhecidas (suportando migracoes
 * do tipo expand/contract), forca numeros inteiros como `Long`, serializa datas como texto ISO-8601
 * e acopla MixIns abstratos para ignorar propriedades computadas em tempo de execucao.
 */
object DomainJson {

    /**
     * Instancia singleton do [JsonMapper] do Jackson 3 pre-configurada para o dominio.
     *
     * ### 1. O que faz
     * Centraliza a instancia de mapeamento JSON imutavel utilizada pelos adaptadores.
     *
     * ### 2. Para que serve
     * Executa operacoes de conversao entre grafos de objetos do Kotlin e representacoes JSON.
     *
     * ### 3. Como funciona
     * Construida com `KotlinModule`, MixIns de ignorar campos computados e convencoes numericas de alta precisao.
     */
    val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.USE_LONG_FOR_INTS)
        .disable(MapperFeature.USE_GETTERS_AS_SETTERS)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .addMixIn(Section::class.java, SectionMixin::class.java)
        .addMixIn(Skeleton::class.java, SkeletonMixin::class.java)
        .addMixIn(SemVer::class.java, SemVerMixin::class.java)
        .addMixIn(ClientContext::class.java, ClientContextMixin::class.java)
        .build()

    /**
     * Serializa uma entidade de dominio em representacao textual JSON.
     *
     * ### 1. O que faz
     * Converte um objeto Kotlin em uma `String` JSON.
     *
     * ### 2. Para que serve
     * Permite salvar entidades em bancos documentais ou logs estruturados.
     *
     * ### 3. Como funciona
     * Delega a chamada para [JsonMapper.writeValueAsString].
     *
     * @param value Objeto a ser serializado.
     * @return String contendo o documento JSON formatado.
     */
    fun <T : Any> write(value: T): String = mapper.writeValueAsString(value)

    /**
     * Serializa uma entidade diretamente em array de bytes (`ByteArray`).
     *
     * ### 1. O que faz
     * Gera a representacao binaria UTF-8 do documento JSON.
     *
     * ### 2. Para que serve
     * Otimiza operacoes de gravacao no Redis e computacao de hashes SHA-256 sem alocar strings intermediarias no heap.
     *
     * ### 3. Como funciona
     * Invoca [JsonMapper.writeValueAsBytes] diretamente sobre o grafo de objetos.
     *
     * @param value Objeto a ser serializado.
     * @return Array de bytes com o conteudo JSON.
     */
    fun writeBytes(value: Any): ByteArray = mapper.writeValueAsBytes(value)

    /**
     * Desserializa uma string JSON para o tipo de dominio solicitado.
     *
     * ### 1. O que faz
     * Converte texto JSON de volta para a instancia tipada do modelo Kotlin.
     *
     * ### 2. Para que serve
     * Restaura dados recuperados de bancos de dados ou arquivos de seed.
     *
     * ### 3. Como funciona
     * Invoca [JsonMapper.readValue] validando os tipos de dados e ignorando campos desconhecidos.
     *
     * @param json Conteudo JSON em formato string.
     * @param type Classe de destino da desserializacao.
     * @return Instancia reconstruida do tipo [T].
     */
    fun <T : Any> read(json: String, type: Class<T>): T = mapper.readValue(json, type)

    /**
     * Desserializa um array binario para a entidade de dominio correspondente.
     *
     * ### 1. O que faz
     * Converte diretamente bytes UTF-8 para o tipo de objeto solicitado.
     *
     * ### 2. Para que serve
     * Habilita a recuperacao de alto desempenho de entradas cacheadas no Redis.
     *
     * ### 3. Como funciona
     * Invoca [JsonMapper.readValue] sobre o array de bytes.
     *
     * @param bytes Bytes brutos do documento JSON.
     * @param type Classe de destino da desserializacao.
     * @return Instancia reconstruida do tipo [T].
     */
    fun <T : Any> read(bytes: ByteArray, type: Class<T>): T = mapper.readValue(bytes, type)

    /**
     * Mixin Jackson para suprimir campos derivados da classe [Section].
     *
     * ### 1. O que faz
     * Anota campos computados da secao com `@JsonIgnore`.
     *
     * ### 2. Para que serve
     * Evita gravar propriedades derivadas que sao recalculadas automaticamente pelo construtor da secao.
     *
     * ### 3. Como funciona
     * Marca a propriedade `capability` para ser ignorada durante serializacao e desserializacao.
     */
    private abstract class SectionMixin {
        @get:JsonIgnore
        abstract val capability: Capability
    }

    /**
     * Mixin Jackson para ignorar propriedades derivadas da classe [Skeleton].
     *
     * ### 1. O que faz
     * Suprime a gravacao de indices e mapas derivados do esqueleto de tela.
     *
     * ### 2. Para que serve
     * Evita redundancia nos dados e garante que `slotOrder` e `requiredSlotIds` sejam recalculados a partir da lista de slots.
     *
     * ### 3. Como funciona
     * Aplica `@JsonIgnore` sobre os getters de `slotOrder` e `requiredSlotIds`.
     */
    private abstract class SkeletonMixin {
        @get:JsonIgnore
        abstract val slotOrder: Map<String, Int>

        @get:JsonIgnore
        abstract val requiredSlotIds: Set<String>
    }

    /**
     * Mixin Jackson para suprimir propriedades calculadas de [SemVer].
     *
     * ### 1. O que faz
     * Impede que valores derivados de versao semantica sejam persistidos.
     *
     * ### 2. Para que serve
     * Mantem o payload enxuto contendo apenas os numeros base `major`, `minor` e `patch`.
     *
     * ### 3. Como funciona
     * Marca `ordinal` e `majorMinor` com `@JsonIgnore`.
     */
    private abstract class SemVerMixin {
        @get:JsonIgnore
        abstract val ordinal: Long

        @get:JsonIgnore
        abstract val majorMinor: String
    }

    /**
     * Mixin Jackson para suprimir propriedades calculadas do [ClientContext].
     *
     * ### 1. O que faz
     * Ignora o objeto SemVer computado a partir do header de versao de schema.
     *
     * ### 2. Para que serve
     * Evita duplicacao de dados na persistencia do contexto do requisitante.
     *
     * ### 3. Como funciona
     * Aplica `@JsonIgnore` sobre a propriedade `parsedSchemaVersion`.
     */
    private abstract class ClientContextMixin {
        @get:JsonIgnore
        abstract val parsedSchemaVersion: SemVer
    }
}
