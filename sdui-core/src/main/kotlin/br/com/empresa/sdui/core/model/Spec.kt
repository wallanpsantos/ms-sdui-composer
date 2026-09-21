package br.com.empresa.sdui.core.model

import java.time.Instant

/**
 * A quem um spec se destina, e o criterio que decide se ele serve a um cliente.
 *
 * [matches] concentra a regra dos tres eixos: plataforma e faixa de app e SO (C), faixa de schema
 * (A) e as capabilities exigidas (B). Uma capability exigida que o cliente nao tem descarta o spec
 * inteiro, diferente de Filter, que omite so a section.
 */
data class Targeting(
    val platform: ClientPlatform,
    val appVersion: VersionRange,
    val osVersion: VersionRange?,
    val schemaVersion: VersionRange,
    val requiredCapabilities: List<Capability>,
    val priority: Int,
    val band: String,
) {
    fun matches(context: ClientContext, effectiveCaps: Set<Capability>): Boolean {
        if (platform != context.platform) return false
        if (!schemaVersion.contains(context.parsedSchemaVersion)) return false
        if (!appVersion.contains(context.appVersion)) return false
        val os = osVersion
        val clientOs = context.osVersion
        if (os != null && clientOs != null && !os.contains(clientOs)) return false
        return requiredCapabilities.isEmpty() || requiredCapabilities.all { it in effectiveCaps }
    }

    fun wireMin(): String = appVersion.min.toString()
    fun wireMax(): String? = appVersion.max?.toString()
    fun wireOsMin(): String? = osVersion?.min?.toOsString()
}

/**
 * A versao publicavel de uma surface: as sections e a quem elas se destinam.
 *
 * Imutavel depois de PUBLISHED — mudar conteudo exige nova revisao, o que torna [specRevisionId]
 * uma referencia estavel para cache, ETag e analytics. O pointer e quem decide qual revisao
 * publicada esta em vigor por plataforma e canal.
 */
data class Spec(
    val specId: String,
    val revision: Int,
    val specRevisionId: String,
    val parentRevision: Int?,
    val status: SpecStatus,
    val surface: String,
    val platform: ClientPlatform,
    val channel: Channel,
    val skeletonId: String,
    val skeletonRevision: Int,
    val targeting: Targeting,
    val sections: List<Section>,
    val checksum: String,
    val publishedAt: Instant?,
    val publishedBy: String?,
    val madeBy: String,
    val experience: String,
) {
    fun matches(context: ClientContext, effectiveCaps: Set<Capability>): Boolean =
        status == SpecStatus.PUBLISHED && targeting.matches(context, effectiveCaps)
}

/**
 * Qual revisao esta em vigor para uma surface, plataforma e canal.
 *
 * O unico estado mutavel da governanca, e por isso o ponto de rollback: [previousSpecRevisionId]
 * guarda para onde voltar e [version] cresce a cada movimento, dando ordenacao e deteccao de
 * concorrencia. Ha um pointer por canal, o que isola canary de stable.
 */
data class Pointer(
    val surface: String,
    val platform: ClientPlatform,
    val channel: Channel,
    val specId: String?,
    val specRevisionId: String?,
    val previousSpecRevisionId: String?,
    val version: Long,
)

/**
 * Pedido de publicacao em fluxo maker-checker.
 *
 * Existe para separar quem propoe de quem aprova: [makerId] abre, [checkerId] decide, e o mesmo
 * ator nao pode ocupar os dois papeis fora do canal interno.
 */
data class PublishRequest(
    val requestId: String,
    val specId: String,
    val revision: Int,
    val specRevisionId: String,
    val surface: String,
    val platform: ClientPlatform,
    val channel: Channel,
    val makerId: String,
    val status: PublishRequestStatus,
    val checkerId: String? = null,
    val reason: String? = null,
)

/** Uma diferenca entre duas revisoes, identificada por caminho. */
data class DiffEntry(
    val path: String,
    val change: String,
    val from: String? = null,
    val to: String? = null,
)

/**
 * O que muda entre duas revisoes, calculado antes de aprovar.
 *
 * [requiredOccupancy] e o campo que importa na revisao humana: mostra se a mudanca altera quantas
 * sections ocupam um slot portante, que e o jeito mais facil de quebrar a home sem perceber.
 */
data class SpecDiff(
    val specId: String,
    val fromRevision: Int?,
    val toRevision: Int,
    val added: List<DiffEntry>,
    val removed: List<DiffEntry>,
    val changed: List<DiffEntry>,
    val requiredOccupancy: List<DiffEntry>,
)

/**
 * Registro imutavel de uma decisao de governanca: quem fez, o que, quando e sobre qual revisao.
 *
 * Guarda de e para qual revisao o pointer andou, para reconstruir o historico de producao.
 */
data class AuditEvent(
    val id: String,
    val ts: java.time.Instant,
    val actorId: String,
    val role: ActorRole,
    val action: String,
    val surface: String,
    val platform: ClientPlatform,
    val channel: Channel,
    val specId: String?,
    val fromRevision: String?,
    val toRevision: String?,
    val requestId: String?,
)

/**
 * Reserva ou resultado de uma chave de idempotencia.
 *
 * Faz o retry de uma operacao administrativa devolver o mesmo resultado em vez de duplicar a
 * publicacao ou conflitar com o proprio efeito anterior.
 *
 * [resultRef] nulo significa reserva em voo: a chave foi tomada e a operacao ainda nao commitou.
 * A distincao importa porque so ela separa "ja fizemos, aqui esta o resultado" de "alguem esta
 * fazendo agora" — sem ela, um retry concorrente executaria a operacao uma segunda vez.
 */
data class IdempotencyRecord(
    val key: String,
    val operation: String,
    val resultRef: String?,
)
