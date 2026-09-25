package br.com.empresa.sdui.adapters.json

import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.port.outbound.PublicationFingerprint
import tools.jackson.databind.SerializationFeature
import java.security.MessageDigest
import java.util.*

/**
 * Calculador de impressao digital canonica de publicacao baseado em Jackson 3 e SHA-256 (`ADR-022`).
 *
 * ### 1. O que faz
 * Gera um digest SHA-256 deterministico e canônico a partir da representacao binaria serializada
 * do par [Spec] e [Skeleton].
 *
 * ### 2. Para que serve
 * Garante a integridade e imutabilidade dos artefatos durante o ciclo de governanca maker-checker:
 * impede que um rascunho de spec ou skeleton sofra adulteracoes entre a submissao pelo autor (maker)
 * e a aprovacao final pelo revisor (checker).
 *
 * ### 3. Como funciona
 * Utiliza o [DomainJson.mapper] com [SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS] ativado para
 * eliminar indeterminismos de ordenacao em mapas de propriedades (`props`) e acoes (`actions`).
 * Normaliza o status do skeleton para [SpecStatus.DRAFT] para que publicacoes reutilizando o mesmo
 * esqueleto estrutural produzam assinaturas coerentes, aplicando a funcao hash `SHA-256` da JVM.
 */
class JsonPublicationFingerprint : PublicationFingerprint {
    private val mapper = DomainJson.mapper.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()

    /**
     * Calcula o hash SHA-256 canônico do par de especificacao e esqueleto.
     *
     * ### 1. O que faz
     * Computa a cadeia identificadora unica de integridade para o par recebido.
     *
     * ### 2. Para que serve
     * Produz o valor do `fingerprint` gravado nas solicitacoes de publicacao e validado nas transicoes de status.
     *
     * ### 3. Como funciona
     * Serializa a lista imutavel contendo o [spec] e uma copia do [skeleton] com status forçado para [SpecStatus.DRAFT]
     * em um array de bytes, submete ao algoritmo `SHA-256` via [MessageDigest] e formata o resultado como
     * `sha256:<hex>` utilizando [HexFormat].
     *
     * @param spec Especificacao de tela contendo secoes, targeting e configuracoes.
     * @param skeleton Esqueleto da tela com a definicao de slots e componentes obrigatorios.
     * @return String contendo o hash hexadecimal prefixado por `sha256:`.
     */
    override fun of(spec: Spec, skeleton: Skeleton): String {
        // Publicar o mesmo skeleton em outro pedido nao muda seu conteudo revisado.
        val bytes = mapper.writeValueAsBytes(listOf(spec, skeleton.copy(status = SpecStatus.DRAFT)))
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
