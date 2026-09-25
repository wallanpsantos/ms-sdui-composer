package br.com.empresa.sdui.api.trace

import org.slf4j.MDC

/**
 * Gerenciador de contexto de diagnostico e rastreabilidade para o ciclo de composicao de tela.
 *
 * ### 1. O que faz
 * Mantem e sincroniza metadados tecnicos da requisicao corrente (surface, plataforma cliente,
 * versoes de schema e aplicativo) em um armazenamento local de thread ([ThreadLocal]) e no [MDC] do SLF4J.
 *
 * ### 2. Para que serve
 * Permite que todas as operacoes executadas durante o ciclo de composicao registrem logs enriquecidos com
 * informacoes tecnicas essenciais, garantindo estrita observabilidade sem trafegar dados pessoais sensiveis
 * (PII) ou identificadores regulados de usuarios.
 *
 * ### 3. Como funciona
 * - **Armazenamento Seguro:** Guarda um mapa imutavel de strings em [values] via [ThreadLocal].
 * - **Sincronizacao com MDC:** Ao abrir o contexto via [open], itera sobre as chaves nao vazias e as registra no [MDC].
 * - **Descarte Mandatorio em Virtual Threads:** Como as Virtual Threads do Java 25 sao montadas e desmontadas
 *   sobre pools de carrier threads subjacentes, a chamada a [close] no bloco `finally` do controlador e
 *   imprescindivel para limpar o [MDC] e invocar `values.remove()`, impedindo vazamento de contexto entre requisicoes.
 */
class ComposeTraceContext {
    /** Armazenamento isolado por thread dos pares de chave-valor de rastreamento da requisicao. */
    private val values = ThreadLocal<Map<String, String>>()

    /**
     * Inicializa o contexto de trace com as informacoes tecnicas da requisicao atual.
     *
     * ### 1. O que faz
     * Atribui o mapa de contexto a thread atual e replica os valores nao vazios para o [MDC] do SLF4J.
     *
     * ### 2. Para que serve
     * Habilita a injecao imediata de atributos de rastreamento nas proximas mensagens de log do pipeline.
     *
     * ### 3. Como funciona
     * Armazena [context] em [values] e executa `MDC.put(key, value)` para cada entrada cujo valor nao seja vazio.
     *
     * @param context Mapa contendo os pares de metadados tecnicos (ex: `surface`, `platform`, `schemaVersion`).
     */
    fun open(context: Map<String, String>) {
        values.set(context)
        context.forEach { (key, value) ->
            if (value.isNotEmpty()) {
                MDC.put(key, value)
            }
        }
    }

    /**
     * Recupera o mapa de valores de rastreamento associado a thread corrente.
     *
     * ### 1. O que faz
     * Retorna os metadados configurados para a requisicao atual ou um mapa vazio caso nao haja contexto aberto.
     *
     * ### 2. Para que serve
     * Permite a consulta dos atributos de rastreamento por outros componentes de observabilidade ou auditoria.
     *
     * ### 3. Como funciona
     * Consulta `values.get()`, retornando o mapa existente ou delegando para `orEmpty()` se nulo.
     *
     * @return Mapa de atributos de trace da requisicao corrente.
     */
    fun get(): Map<String, String> = values.get().orEmpty()

    /**
     * Encerra o contexto de trace da requisicao corrente, liberando recursos.
     *
     * ### 1. O que faz
     * Remove as chaves registradas do [MDC] do SLF4J e descarta a referencia do [ThreadLocal].
     *
     * ### 2. Para que serve
     * Previne contaminacao cruzada de dados de log entre requisicoes distintas atendidas pela JVM.
     *
     * ### 3. Como funciona
     * Itera sobre todas as chaves do mapa armazenado invocando `MDC.remove(it)` e subsequentemente executa `values.remove()`.
     */
    fun close() {
        values.get()?.keys?.forEach { MDC.remove(it) }
        values.remove()
    }
}
