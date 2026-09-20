package br.com.empresa.sdui.api.trace

/**
 * Contexto de diagnostico da requisicao corrente, para correlacionar logs de uma composicao.
 *
 * Guarda apenas campos tecnicos — surface, plataforma, schema — e nunca identificacao de usuario.
 * Preso a thread da requisicao, por isso o controller precisa sempre fechar no finally: com
 * virtual threads o valor nao pode vazar para a proxima requisicao.
 */
class ComposeTraceContext {
    private val values = ThreadLocal<Map<String, String>>()

    fun open(context: Map<String, String>) {
        values.set(context)
    }

    fun get(): Map<String, String> = values.get().orEmpty()

    fun close() {
        values.remove()
    }
}
