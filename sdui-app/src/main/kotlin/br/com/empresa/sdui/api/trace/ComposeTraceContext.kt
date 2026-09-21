package br.com.empresa.sdui.api.trace

import org.slf4j.MDC

/**
 * Contexto de diagnostico da requisicao corrente, para correlacionar logs de uma composicao.
 *
 * Guarda apenas campos tecnicos — surface, plataforma, schema — e nunca identificacao de usuario.
 * Preso a thread da requisicao e sincronizado com o MDC do SLF4J, por isso o controller precisa
 * sempre fechar no finally: com virtual threads o valor nao pode vazar para a proxima requisicao.
 */
class ComposeTraceContext {
    private val values = ThreadLocal<Map<String, String>>()

    fun open(context: Map<String, String>) {
        values.set(context)
        context.forEach { (key, value) ->
            if (value.isNotEmpty()) {
                MDC.put(key, value)
            }
        }
    }

    fun get(): Map<String, String> = values.get().orEmpty()

    fun close() {
        values.get()?.keys?.forEach { MDC.remove(it) }
        values.remove()
    }
}

