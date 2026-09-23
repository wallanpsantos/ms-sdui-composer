package br.com.empresa.sdui.adapters.json

import br.com.empresa.sdui.core.model.Capability
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
 * Serializacao do modelo de dominio para os adapters persistentes (MongoDB e Redis).
 *
 * O dominio e puro e nao conhece Jackson; a configuracao que o torna persistivel mora aqui, num
 * mapper proprio e explicito, independente do mapper HTTP do Spring. Assim uma mudanca de default
 * do Spring Boot nao altera silenciosamente o formato do que ja esta gravado.
 *
 * - Propriedades derivadas (`Section.capability`, `Skeleton.slotOrder`, `SemVer.ordinal`...) nao
 *   sao gravadas: sao recalculadas pelo construtor na leitura.
 * - Numeros inteiros nas props voltam como `Long`, a mesma convencao do seed; decimais, `Double`.
 * - Instantes vao como texto ISO-8601, sem perda de precisao.
 * - Campo desconhecido e ignorado: um binario antigo le documento gravado por um mais novo, o que
 *   permite migracao expand/contract.
 */
object DomainJson {
    val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.USE_LONG_FOR_INTS)
        .disable(MapperFeature.USE_GETTERS_AS_SETTERS)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .addMixIn(Section::class.java, SectionMixin::class.java)
        .addMixIn(Skeleton::class.java, SkeletonMixin::class.java)
        .addMixIn(SemVer::class.java, SemVerMixin::class.java)
        .build()

    fun <T : Any> write(value: T): String = mapper.writeValueAsString(value)

    fun writeBytes(value: Any): ByteArray = mapper.writeValueAsBytes(value)

    fun <T : Any> read(json: String, type: Class<T>): T = mapper.readValue(json, type)

    fun <T : Any> read(bytes: ByteArray, type: Class<T>): T = mapper.readValue(bytes, type)

    private abstract class SectionMixin {
        @get:JsonIgnore
        abstract val capability: Capability
    }

    private abstract class SkeletonMixin {
        @get:JsonIgnore
        abstract val slotOrder: Map<String, Int>

        @get:JsonIgnore
        abstract val requiredSlotIds: Set<String>
    }

    private abstract class SemVerMixin {
        @get:JsonIgnore
        abstract val ordinal: Long

        @get:JsonIgnore
        abstract val majorMinor: String
    }
}
