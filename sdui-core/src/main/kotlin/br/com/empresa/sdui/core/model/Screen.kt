package br.com.empresa.sdui.core.model

import java.time.Instant

/**
 * A arvore de UI pronta, ainda no modelo de dominio: saida do pipeline e unidade de cache.
 *
 * E o que se guarda no cache e como last good. Nao carrega nada especifico de usuario, o que
 * permite compartilhar a mesma arvore entre todos os clientes de mesma plataforma, schema, faixa
 * de app, capabilities e canal — exatamente os campos da chave de cache.
 */
data class ComposedScreen(
    val surface: String,
    val platform: ClientPlatform,
    val schemaVersion: String,
    val specRevisionId: String,
    val skeletonId: String,
    val skeletonHash: String,
    val etag: String,
    val generatedAt: Instant,
    val locale: String,
    val channel: Channel,
    val fallback: Boolean,
    val fallbackReason: FallbackReason,
    val omitted: List<OmittedSection>,
    val client: ClientContext,
    val targeting: Targeting,
    val experience: String,
    val skeleton: Skeleton,
    val sections: List<Section>,
)

/**
 * Montagem das chaves de cache, num lugar so.
 *
 * A chave de arvore identifica a revisao ja escolhida, e nao o contexto que levou ate ela:
 * plataforma, schema, specRevisionId, hash de capabilities e canal. Chavear pela revisao e o que
 * mantem cache e selecao coerentes — o targeting discrimina por versao completa do app e por
 * versao de SO, dimensoes que nao cabem numa chave sem explodir a cardinalidade. Como a selecao
 * ja aconteceu, duas requisicoes so compartilham a entrada quando chegaram a mesma revisao.
 *
 * [containsUserId] e a verificacao que impede uma chave por usuario de existir — arvore de usuario
 * nao e cacheada, e o pipeline afirma isso com check() antes de gravar ou ler.
 */
object RedisKeys {
    fun spec(specRevisionId: String, platform: ClientPlatform): String =
        "sdui:spec:$specRevisionId:${platform.wire()}"

    fun tree(
        surface: String,
        platform: ClientPlatform,
        schema: String,
        specRevisionId: String,
        capsHash: String,
        channel: Channel,
    ): String = "sdui:tree:$surface:${platform.wire()}:$schema:$specRevisionId:$capsHash:${channel.wire()}"

    fun section(projection: String, id: String): String = "sdui:section:$projection:$id"

    fun lastGood(surface: String, platform: ClientPlatform, channel: Channel): String =
        "sdui:lastgood:$surface:${platform.wire()}:${channel.wire()}"

    fun singleflight(treeKey: String): String = "sdui:sf:$treeKey"

    fun containsUserId(key: String): Boolean = key.contains("userId", ignoreCase = true)
}

/**
 * Monta o ETag da arvore servida.
 *
 * Fraco por ser derivado de identificadores e nao do byte a byte do corpo: dois clientes na mesma
 * revisao e mesmo contexto recebem o mesmo ETag, o que faz If-None-Match devolver 304 e poupar a
 * serializacao inteira.
 *
 * Os quatro campos sao os mesmos que distinguem uma arvore de outra. O [capsHash] entra porque
 * clientes com capabilities diferentes recebem sections diferentes: sem ele, duas representacoes
 * distintas do mesmo recurso compartilhariam ETag e uma revalidacao poderia devolver 304 para
 * conteudo que o cliente nao tem. Entra abreviado — o hash completo tornaria o header longo sem
 * ganho pratico, ja que o conjunto de capabilities em uso e pequeno.
 */
object ETagFactory {
    private const val CAPS_PREFIX_LENGTH: Int = 12

    fun of(
        specRevisionId: String,
        platform: ClientPlatform,
        schemaVersion: String,
        capsHash: String,
    ): String {
        val caps = capsHash.take(CAPS_PREFIX_LENGTH)
        return "W/\"$specRevisionId-${platform.wire()}-$schemaVersion-$caps\""
    }
}
