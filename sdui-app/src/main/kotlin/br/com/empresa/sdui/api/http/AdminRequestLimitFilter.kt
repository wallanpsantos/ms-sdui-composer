package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filtro HTTP que limita tamanho de payload e concorrencia de requisicoes no plano administrativo (ADR-022).
 *
 * ### 1. O que faz
 * Intercepta chamadas direcionadas aos endpoints administrativos (`/admin`), impondo limites estritos
 * de tamanho de corpo (em bytes) e um semaforo de concorrencia maxima antes que o parser Jackson construa
 * o grafo de objetos em memoria.
 *
 * ### 2. Para que serve
 * Protege a JVM contra exaustao de heap e saturacao de processamento (Denial of Service) provocadas por
 * payloads JSON excessivamente grandes ou rajadas simultâneas de chamadas administrativas pesadas.
 *
 * ### 3. Como funciona
 * - **Escopo de Atuacao:** Executa exclusivamente sobre caminhos com prefixo `/admin` via [shouldNotFilter].
 * - **Autenticacao Preliminar:** Rejeita imediatamente com HTTP 403 Forbidden requisicoes que nao enviem os
 *   cabecalhos `Actor-Id` e `Actor-Role` devidamente formatados.
 * - **Controle de Concorrencia (Bulkhead):** Utiliza um [Semaphore] com capacidade [maxConcurrentRequests].
 *   Se nao houver permissao imediata (`tryAcquire`), responde HTTP 503 com cabecalho `Retry-After` sorteado
 *   via [adminRetryAfterSeconds], evitando sobrecarga do plano administrativo.
 * - **Teto de Bytes do Corpo:** Avalia preliminarmente o cabecalho `Content-Length`. Caso ausente ou adulterado,
 *   le ativamente ate `maxBodyBytes + 1` do stream: se exceder, responde HTTP 413 Payload Too Large.
 * - **Bufferizacao Transparente:** Envolve a requisicao em um [BufferedAdminRequest] contendo os bytes lidos,
 *   permitindo que o Spring MVC e o Jackson leiam o stream normalmente sem erro de stream fechado.
 *
 * @property maxBodyBytes Quantidade maxima permitida em bytes para o corpo da requisicao (padrao: 1 MB).
 * @param maxConcurrentRequests Quantidade maxima de chamadas administrativas simultâneas (padrao: 8).
 * @property metrics Gravador de metricas operacionais Micrometer.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class AdminRequestLimitFilter(
    @Value("\${sdui.admin-max-body-bytes:1048576}") private val maxBodyBytes: Int,
    @Value("\${sdui.admin-max-concurrent-requests:8}") maxConcurrentRequests: Int,
    private val metrics: MetricsRecorder,
) : OncePerRequestFilter() {
    private val permits = Semaphore(maxConcurrentRequests)

    init {
        require(maxBodyBytes in 1 until Int.MAX_VALUE)
        require(maxConcurrentRequests > 0)
    }

    /**
     * Determina se o filtro deve ignorar a requisicao corrente.
     *
     * ### 1. O que faz
     * Avalia o caminho da requisicao e decide se o filtro administrativo deve ser aplicado ou ignorado.
     *
     * ### 2. Para que serve
     * Isola o plano de leitura de alta vazao (`/v1/surfaces`) das travas e verificacoes do plano administrativo.
     *
     * ### 3. Como funciona
     * Extrai o caminho servlet relativo e verifica se inicia com `/admin`. Retorna `true` (nao filtrar) para
     * qualquer rota que nao pertenca ao plano administrativo.
     *
     * @param request A requisicao HTTP recebida.
     * @return `true` se o filtro deve ser ignorado, `false` se deve ser executado.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.servletPath.ifEmpty { request.requestURI.removePrefix(request.contextPath) }
// Inclui parametros de matriz (/admin;param=.../v1), removidos pelo roteamento MVC.
        return !path.startsWith("/admin")
    }

    /**
     * Executa a logica de interceptacao, validacao e controle de taxa da requisicao.
     *
     * ### 1. O que faz
     * Valida presenca do ator, tamanho do corpo e limites de concorrencia antes de repassar a cadeia de filtros.
     *
     * ### 2. Para que serve
     * Aplica os controles de seguranca e resiliencia de entrada estabelecidos na `ADR-022`.
     *
     * ### 3. Como funciona
     * 1. Verifica cabecalhos `Actor-Id` e `Actor-Role`. Se invalidos, invoca [reject] com status 403.
     * 2. Confere `Content-Length`. Se maior que [maxBodyBytes], rejeita com status 413.
     * 3. Tenta adquirir permissao no semaforo [permits]. Se indisponivel, responde 503 com `Retry-After`.
     * 4. Le os bytes do corpo ate o limite de seguranca (`maxBodyBytes + 1`).
     * 5. Encaminha a requisicao envelopada em [BufferedAdminRequest] e garante liberacao do semaforo no `finally`.
     *
     * @param request Requisicao HTTP recebida.
     * @param response Resposta HTTP a ser devolvida.
     * @param filterChain Cadeia de filtros de processamento HTTP do Servlet container.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.getHeader("Actor-Id").isNullOrBlank() || ActorRole.parse(request.getHeader("Actor-Role")) == null) {
            reject(response, 403, "FORBIDDEN", "ator ausente ou invalido", "denied")
            return
        }
        if (request.contentLengthLong > maxBodyBytes) {
            reject(response, 413, "REQUEST_TOO_LARGE", "corpo acima do limite", "body_too_large")
            return
        }
        if (!permits.tryAcquire()) {
            response.setHeader("Retry-After", adminRetryAfterSeconds())
            reject(response, 503, "ADMIN_UNAVAILABLE", "limite de requisicoes administrativas", "unavailable")
            return
        }
        try {
// Nao confiar em Content-Length: chunked e comprimento falso tambem tem teto.
            val bytes = request.inputStream.readNBytes(maxBodyBytes + 1)
            if (bytes.size > maxBodyBytes) {
                reject(response, 413, "REQUEST_TOO_LARGE", "corpo acima do limite", "body_too_large")
                return
            }
            filterChain.doFilter(BufferedAdminRequest(request, bytes), response)
        } finally {
            permits.release()
        }
    }

    /**
     * Emite uma resposta HTTP de rejeicao estruturada em JSON sem depender do Spring MVC.
     *
     * ### 1. O que faz
     * Escreve diretamente no fluxo de saida da resposta o status, cabecalhos e o envelope JSON de erro.
     *
     * ### 2. Para que serve
     * Permite abortar requisicoes invalidas ainda na camada de filtros de forma padronizada e segura.
     *
     * ### 3. Como funciona
     * Registra metrica de erro administrativo, define o status HTTP, configura codificacao UTF-8,
     * define `Content-Type: application/json` e serializa um JSON seguro contendo apenas constantes literais.
     *
     * @param response Resposta HTTP na qual os dados serao escritos.
     * @param status Codigo de status HTTP a ser retornado (ex: 403, 413, 503).
     * @param code Codigo estavel de erro da aplicacao.
     * @param message Mensagem explicativa estatica.
     * @param error Rotulo para identificacao na tag da metrica.
     */
    private fun reject(response: HttpServletResponse, status: Int, code: String, message: String, error: String) {
        runCatching { metrics.increment(MetricNames.ADMIN_ERROR, mapOf("error" to error)) }
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
// Apenas constantes internas: nenhum texto do chamador e interpolado no JSON.
        response.writer.write("{\"code\":\"$code\",\"message\":\"$message\",\"details\":[]}")
    }
}

/**
 * Calcula o tempo de espera do cabecalho `Retry-After` para rejeicoes 503 do plano administrativo.
 *
 * ### 1. O que faz
 * Gera uma string numerica com valor aleatorio entre 15 e 30 segundos.
 *
 * ### 2. Para que serve
 * Aplica jitter aleatorio no retry dos operadores para impedir o efeito de manada (thundering herd).
 *
 * ### 3. Como funciona
 * Utiliza [ThreadLocalRandom.current] para sortear um valor no intervalo [15, 30] segundos e converte para string.
 *
 * @return String contendo o valor do intervalo em segundos para o cabecalho `Retry-After`.
 */
internal fun adminRetryAfterSeconds(): String = ThreadLocalRandom.current().nextLong(15, 31).toString()

/**
 * Wrapper de requisicao HTTP que permite a re-leitura do corpo previamente consumido pelo filtro.
 *
 * ### 1. O que faz
 * Encapsula o [HttpServletRequest] original substituindo o stream de entrada por um leitor em memoria ([ByteArrayInputStream]).
 *
 * ### 2. Para que serve
 * Permite que componentes downstream (como os conversores Jackson do Spring MVC) leiam os bytes da requisicao
 * mesmo apos a inspecao preliminar de tamanho executada pelo filtro.
 *
 * ### 3. Como funciona
 * Envolve o array de bytes em um [ServletInputStream] customizado e substitui os metodos [getInputStream],
 * [getReader], [getContentLength] e [getContentLengthLong].
 *
 * @param request Requisicao HTTP original sendo envelopada.
 * @param bytes Array de bytes contendo o corpo completo da requisicao bufferizado em memoria.
 */
private class BufferedAdminRequest(
    request: HttpServletRequest,
    private val bytes: ByteArray,
) : HttpServletRequestWrapper(request) {

    private val stream = object : ServletInputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        override fun read(): Int = delegate.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
        override fun isFinished(): Boolean = delegate.available() == 0
        override fun isReady(): Boolean = true
        override fun setReadListener(listener: ReadListener) {
            throw IllegalStateException("plano administrativo usa Servlet bloqueante")
        }
    }

    /**
     * Retorna o fluxo de entrada servlet baseado no buffer em memoria.
     *
     * @return Instancia de [ServletInputStream] apontando para os bytes bufferizados.
     */
    override fun getInputStream(): ServletInputStream = stream

    /**
     * Retorna um leitor de caracteres baseado no buffer de entrada em memoria.
     *
     * @return Instancia de [BufferedReader] configurada com a codificacao da requisicao.
     */
    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(stream, characterEncoding ?: "UTF-8"))

    /**
     * Retorna o tamanho exato do corpo em bytes.
     *
     * @return Quantidade de bytes contidos no buffer.
     */
    override fun getContentLength(): Int = bytes.size

    /**
     * Retorna o tamanho exato do corpo em formato Long.
     *
     * @return Quantidade de bytes contidos no buffer como Long.
     */
    override fun getContentLengthLong(): Long = bytes.size.toLong()
}
