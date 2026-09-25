package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.api.http.CorrelationIdFilter.Companion.REQUEST_ID_REGEX
import br.com.empresa.sdui.core.model.Surfaces
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.*

/**
 * Filtro HTTP que estabelece o identificador de correlacao e ponto de entrada para cada requisicao.
 *
 * ### 1. O que faz
 * Captura ou gera um identificador unico de requisicao (`requestId`), identifica a categoria da rota
 * (`entryPoint`) e injeta essas informacoes no contexto de diagnostico mapeado ([MDC]) do SLF4J durante
 * todo o ciclo de atendimento.
 *
 * ### 2. Para que serve
 * Garante rastreabilidade ponta a ponta (end-to-end tracing) nos logs estruturados do BFF SDUI, permitindo
 * correlacionar todas as linhas de log de uma mesma requisicao sem expor cabecalhos nao padronizados na resposta
 * (em conformidade com o RFC 6648 que desencoraja headers `X-`).
 *
 * ### 3. Como funciona
 * - **Extracao do Request ID:** Procura pelos cabecalhos `X-Request-Id` ou `Request-Id`. Se presente e compativel
 *   com o formato seguro alfanumerico ([REQUEST_ID_REGEX]), utiliza o valor recebido; caso contrario, gera um novo [UUID] v4.
 * - **Classificacao do Entry Point:** Analisa a URI para classificar a chamada em pontos de entrada canônicos:
 *   surfaces cadastradas (`home`, `catalog`), rotas administrativas (`admin`), endpoints operacionais (`actuator`)
 *   ou HTTP generico.
 * - **Isolamento de Concorrencia com Virtual Threads:** Insere as chaves no [MDC] no inicio e obrigatoriamente
 *   as remove no bloco `finally`, prevenindo vazamento de contexto entre Virtual Threads reaproveitadas no carrier pool.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {

    /**
     * Processa a requisicao HTTP atribuindo identificadores de rastreamento ao [MDC].
     *
     * ### 1. O que faz
     * Intercepta a chamada, injeta `requestId` e `entryPoint` no contexto de log e repassa a execucao para a cadeia de filtros.
     *
     * ### 2. Para que serve
     * Assegura que qualquer log emitido durante o processamento da requisicao contenha os metadados de correlacao.
     *
     * ### 3. Como funciona
     * 1. Extrai ou gera o `requestId` validando contra o regex de seguranca.
     * 2. Determina o `entryPoint` a partir da URI da requisicao.
     * 3. Registra ambos no [MDC] via `MDC.put`.
     * 4. Executa `filterChain.doFilter(request, response)`.
     * 5. No bloco `finally`, remove rigorosamente as entradas do [MDC] com `MDC.remove`.
     *
     * @param request Requisicao HTTP recebida.
     * @param response Resposta HTTP a ser devolvida.
     * @param filterChain Cadeia de processamento de filtros HTTP.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawHeader = request.getHeader(HEADER_X_REQUEST_ID)?.trim()
            ?: request.getHeader(HEADER_REQUEST_ID)?.trim()

        val requestId = if (!rawHeader.isNullOrEmpty() && REQUEST_ID_REGEX.matches(rawHeader)) {
            rawHeader
        } else {
            UUID.randomUUID().toString()
        }

        val uri = request.requestURI.orEmpty()
        val entryPoint = when {
            uri.startsWith(SURFACES_PREFIX) -> surfaceEntryPoint(uri)
            uri.startsWith("/admin") -> ENTRY_POINT_ADMIN
            uri.startsWith("/actuator") -> ENTRY_POINT_ACTUATOR
            else -> ENTRY_POINT_HTTP
        }

        MDC.put(MDC_KEY_REQUEST_ID, requestId)
        MDC.put(MDC_KEY_ENTRY_POINT, entryPoint)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY_ENTRY_POINT)
            MDC.remove(MDC_KEY_REQUEST_ID)
        }
    }

    /**
     * Mapeia a URI de uma surface para um identificador de ponto de entrada canonico.
     *
     * ### 1. O que faz
     * Extrai o nome da surface da URL e o valida contra a allowlist de surfaces conhecidas.
     *
     * ### 2. Para que serve
     * Impede que valores arbitrarios ou caminhos maliciosos sejam copiados diretamente para as tags de log do MDC.
     *
     * ### 3. Como funciona
     * Remove o prefixo `/v1/surfaces/`, extrai o segmento inicial do caminho e consulta [Surfaces.find]. Se for uma surface
     * homologada, devolve seu ID proprio (`home`, `catalog`); caso contrario, retorna o identificador generico `surface`.
     *
     * @param uri URI completa da requisicao.
     * @return Identificador normalizado do ponto de entrada da surface.
     */
    private fun surfaceEntryPoint(uri: String): String {
        val segment = uri.removePrefix(SURFACES_PREFIX).substringBefore('/')
        return Surfaces.find(segment)?.id ?: ENTRY_POINT_SURFACE
    }

    /**
     * Constantes de identificacao de cabecalhos, chaves MDC e rotulos de entrada.
     *
     * ### 1. O que faz
     * Centraliza os nomes de cabecalhos de correlacao, chaves MDC e valores padrao de pontos de entrada.
     *
     * ### 2. Para que serve
     * Padroniza os literais de rastreamento em todo o ciclo de logging e observabilidade do servico.
     *
     * ### 3. Como funciona
     * Expoe constantes imutaveis e a expressao regular [REQUEST_ID_REGEX] utilizada na higienizacao de cabecalhos.
     */
    companion object {
        /** Cabecalho HTTP de entrada convencional para correlacao (`X-Request-Id`). */
        const val HEADER_X_REQUEST_ID: String = "X-Request-Id"

        /** Cabecalho HTTP alternativo compativel com o padrao RFC 6648 (`Request-Id`). */
        const val HEADER_REQUEST_ID: String = "Request-Id"

        /** Chave MDC utilizada para armazenar o ID unico da requisicao. */
        const val MDC_KEY_REQUEST_ID: String = "requestId"

        /** Chave MDC utilizada para armazenar a categoria do ponto de entrada. */
        const val MDC_KEY_ENTRY_POINT: String = "entryPoint"

        /** Rotulo para requisicoes direcionadas a surface Home. */
        const val ENTRY_POINT_HOME: String = "home"

        /** Rotulo generico para requisicoes sob `/v1/surfaces/` fora da allowlist. */
        const val ENTRY_POINT_SURFACE: String = "surface"

        /** Prefixo padrao das rotas de composicao de surfaces de UI. */
        private const val SURFACES_PREFIX: String = "/v1/surfaces/"

        /** Rotulo para requisicoes direcionadas ao plano administrativo (`/admin`). */
        const val ENTRY_POINT_ADMIN: String = "admin"

        /** Rotulo para requisicoes direcionadas aos endpoints do Spring Actuator (`/actuator`). */
        const val ENTRY_POINT_ACTUATOR: String = "actuator"

        /** Rotulo padrao para rotas HTTP gerais nao enquadradas nos casos anteriores. */
        const val ENTRY_POINT_HTTP: String = "http"

        /** Expressao regular para validacao e higienizacao de identificadores de requisicao recebidos. */
        private val REQUEST_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
