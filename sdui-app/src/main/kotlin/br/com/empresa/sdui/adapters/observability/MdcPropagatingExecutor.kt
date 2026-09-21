package br.com.empresa.sdui.adapters.observability

import org.slf4j.MDC
import java.util.concurrent.Executor

/**
 * Executor que propaga o mapa de contexto MDC (SLF4J) para threads assincronas ou virtuais.
 *
 * Captura o MDC da thread disparadora e instala na thread de execucao do Runnable, restaurando o
 * estado anterior no finally. Garante continuidade de rastreabilidade (requestId, entryPoint, etc.)
 * no fan-out sem poluir as camadas internas do orchestrator com dependencias de logging.
 */
class MdcPropagatingExecutor(private val delegate: Executor) : Executor {
    override fun execute(command: Runnable) {
        val contextMap = MDC.getCopyOfContextMap()
        delegate.execute {
            val previous = MDC.getCopyOfContextMap()
            if (contextMap != null) {
                MDC.setContextMap(contextMap)
            } else {
                MDC.clear()
            }
            try {
                command.run()
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous)
                } else {
                    MDC.clear()
                }
            }
        }
    }
}
