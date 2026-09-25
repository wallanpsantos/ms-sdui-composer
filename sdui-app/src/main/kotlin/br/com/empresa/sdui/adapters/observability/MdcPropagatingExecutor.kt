package br.com.empresa.sdui.adapters.observability

import org.slf4j.MDC
import java.util.concurrent.Executor

/**
 * Executor que propaga o mapa de contexto MDC (SLF4J) para threads assíncronas e Virtual Threads.
 *
 * ### 1. O que faz
 * Encapsula um [Executor] padrão capturando e transferindo os dados de diagnóstico de logging da thread chamadora.
 *
 * ### 2. Para que serve
 * Garantir a continuidade de rastreabilidade (`requestId`, `entryPoint`, etc.) durante despachos paralelos
 * ou operações assíncronas de fan-out no Java 25, impedindo perda de correlação em logs sem acoplar o
 * orquestrador com frameworks de logging.
 *
 * ### 3. Como funciona
 * Antes de delegar o comando, obtém uma cópia do MDC na thread de origem. Na thread de destino do [delegate],
 * salva o contexto prévio da thread trabalhadora, instala o contexto propagado, executa o [Runnable] sob `try`
 * e restaura o contexto anterior no `finally`.
 */
class MdcPropagatingExecutor(private val delegate: Executor) : Executor {
    /**
     * Submete um comando [Runnable] propagando o contexto MDC para a thread de execução.
     *
     * ### 1. O que faz
     * Intercepta a submissão da tarefa para transportar o mapa de contexto de logging.
     *
     * ### 2. Para que serve
     * Manter identificadores de rastreamento presentes nos logs gerados pelas tarefas assíncronas.
     *
     * ### 3. Como funciona
     * Copia o mapa de contexto da thread atual e agenda no [delegate] um wrapper que instala o contexto,
     * executa `command.run()` e limpa/restaura o contexto no `finally`.
     */
    override fun execute(command: Runnable) {
        val contextMap = MDC.getCopyOfContextMap()
        delegate.execute {
            val previous = MDC.getCopyOfContextMap()
            install(contextMap)
            try {
                command.run()
            } finally {
                install(previous)
            }
        }
    }

    /**
     * Instala o mapa de contexto no MDC ou limpa o contexto se for nulo.
     *
     * ### 1. O que faz
     * Aplica o [contextMap] no MDC da thread corrente.
     *
     * ### 2. Para que serve
     * Padronizar a instalação, lidando de forma segura com o valor `null` quando o MDC está vazio.
     *
     * ### 3. Como funciona
     * Se [contextMap] for não-nulo, chama `MDC.setContextMap(contextMap)`; se nulo, chama `MDC.clear()`.
     */
    private fun install(contextMap: Map<String, String>?) {
        if (contextMap != null) MDC.setContextMap(contextMap) else MDC.clear()
    }
}
