package br.com.empresa.sdui.core.cache

import br.com.empresa.sdui.core.model.Capability
import java.security.MessageDigest
import java.util.*

/**
 * Formatador hexadecimal compartilhado, imutável e seguro para concorrência (`Thread-Safe`).
 *
 * ### 1. O que faz
 * Converte arrays de bytes em representações de texto hexadecimal minúsculo.
 *
 * ### 2. Para que serve
 * Aplica uma otimização de alta performance resultante do ciclo de revisão técnica: elimina a invocação de
 * `String.format("%02x", byte)` byte a byte que ocorria a cada requisição de composição, reduzindo sensivelmente
 * a alocação de objetos intermediários e pausas de garbage collection no hot path.
 *
 * ### 3. Como funciona
 * Instancia a API nativa [HexFormat] do Java 25 via `HexFormat.of()`. Como a instância é imutável e stateless,
 * é compartilhada estaticamente entre todas as Virtual Threads sem risco de contenção ou condições de corrida.
 */
private val HEX: HexFormat = HexFormat.of()

/**
 * Utilitário determinístico de geração de hash para conjuntos de capacidades visuais.
 *
 * ### 1. O que faz
 * Sintetiza uma coleção de instâncias de [Capability] em uma assinatura criptográfica estável SHA-256 formatada em hexadecimal.
 *
 * ### 2. Para que serve
 * Compõe o fragmento `capsHash` na chave do cache de telas hidratadas (`treeCache`), garantindo que clientes que
 * compartilhem rigorosamente o mesmo perfil de capacidades de renderização possam reutilizar a mesma árvore de UI
 * pré-computada em memória ou no Redis.
 *
 * ### 3. Como funciona
 * 1. **Mapeamento e Normalização:** Converte cada [Capability] em sua representação de linha (`type@typeVersion` via `Capability.wire`).
 * 2. **Ordenação Canônica Estável:** Ordena lexicograficamente as strings antes de aplicar o resumo (`sorted()`). Essa etapa
 *    é crítica para a eficiência do cache: dois clientes com as mesmas capacidades enviadas em ordem distinta produzem
 *    rigorosamente a mesma assinatura, prevenindo a duplicação e fragmentação inútil de entradas de cache.
 * 3. **Cálculo de Digest:** Concatena os elementos com vírgulas e gera o digest SHA-256 dos bytes UTF-8 via [MessageDigest].
 * 4. **Formatação Otimizada:** Formata os bytes do digest utilizando a instância de alta performance [HEX].
 */
object CapsHash {
    /**
     * Calcula a assinatura SHA-256 canônica para a coleção de capacidades informada.
     *
     * ### 1. O que faz
     * Produz o hash SHA-256 determinístico das capacidades recebidas.
     *
     * ### 2. Para que serve
     * Gera a chave de dispersão (`capsHash`) utilizada na indexação de cache de árvore e no isolamento de compatibilidade.
     *
     * ### 3. Como funciona
     * Transforma as capacidades em texto wire, ordena em ordem alfabética, une com vírgula, calcula o SHA-256 e converte
     * para hexadecimal via [HEX].
     *
     * @param capabilities Coleção de capacidades a serem sintetizadas em hash.
     * @return String hexadecimal de 64 caracteres contendo o hash SHA-256 determinístico.
     */
    fun sha256(capabilities: Collection<Capability>): String {
        val ordered = capabilities.map { it.wire() }.sorted().joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256").digest(ordered.toByteArray(Charsets.UTF_8))
        return HEX.formatHex(digest)
    }
}
