package br.com.empresa.sdui.core.negotiate

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ContextValidation
import br.com.empresa.sdui.core.model.ContextViolation
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.SemVer

/**
 * Primeiro passo do pipeline de composição Server-Driven UI: negociação e validação de contexto.
 *
 * ### 1. O que faz
 * Transforma os cabeçalhos HTTP brutos recebidos do cliente móvel (encapsulados em [NegotiateHeaders]) em
 * uma representação tipada, segura e imutável de [ClientContext] (ou em uma lista de violações [ContextViolation]).
 *
 * ### 2. Para que serve
 * Atua como a primeira linha de defesa do BFF contra requisições malformadas ou incompatíveis com o protocolo,
 * garantindo que apenas solicitações válidas alcancem as fases posteriores do pipeline (`Select`, `Filter`, `Hydrate`, `Compose`).
 *
 * ### 3. Como funciona
 * Avalia os três eixos de compatibilidade arquiteturais:
 * - **Eixo A (Protocolo e Envelope):** Valida `UI-Schema-Version` (deve ser numérico e pertencer a [MvpCatalog.SUPPORTED_SCHEMA_VERSIONS])
 *   e `API-Version` (deve ser "1").
 * - **Eixo B (Renderização e Capacidades):** Faz o parsing da lista opcional `Component-Capabilities` via [Capability.parseList].
 * - **Eixo C (Dispositivo e Versão):** Valida a plataforma [ClientPlatform], versão de aplicativo via [SemVer.parseThreePart],
 *   formato numérico de build, formato de localidade BCP-47 em `Accept-Language` e versão de SO opcional.
 *
 * Adota a estratégia de acúmulo integral de erros: em vez de interromper o processamento no primeiro erro (fail-fast parcial),
 * coleta todas as violações encontradas para que o cliente móvel possa corrigir todos os problemas de uma só vez.
 * Cabeçalhos inválidos ou ausentes constituem erro esperado de cliente e resultam em [ContextValidation.Invalid]
 * (convertido em HTTP 400 Bad Request pela camada web), garantindo que **nunca** seja disparado um HTTP 500 indevido.
 */
object Negotiate {
    /**
     * Expressão regular para validação do identificador de build do aplicativo (`Client-Build`).
     *
     * ### 1. O que faz
     * Valida se o identificador de compilação é estritamente numérico, contendo entre 1 e 10 dígitos.
     *
     * ### 2. Para que serve
     * Protege contra valores não numéricos ou tentativas de injeção em cabeçalhos de build.
     *
     * ### 3. Como funciona
     * Aplica o padrão `^\d{1,10}$` sobre a string informada.
     */
    private val BUILD = Regex("""^\d{1,10}$""")

    /**
     * Expressão regular para validação de formato de idioma e localidade (`Accept-Language`).
     *
     * ### 1. O que faz
     * Valida se a localidade primária segue a especificação canônica BCP-47.
     *
     * ### 2. Para que serve
     * Garante que o locale do cliente seja interpretável pelos formatadores de texto, moeda e datas do BFF.
     *
     * ### 3. Como funciona
     * Aplica o padrão `^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$` sobre a tag de idioma extraída.
     */
    private val LOCALE = Regex("""^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$""")

    /**
     * Expressão regular para validação do formato numérico da versão do schema SDUI (`UI-Schema-Version`).
     *
     * ### 1. O que faz
     * Valida se a versão de schema declarada consiste em um número inteiro positivo de até 10 dígitos.
     *
     * ### 2. Para que serve
     * Previne erros de conversão numérica e descarta valores malformados antes de consultar o catálogo de schemas suportados.
     *
     * ### 3. Como funciona
     * Aplica o padrão `^\d{1,10}$` sobre a versão informada.
     */
    private val SCHEMA = Regex("""^\d{1,10}$""")

    /**
     * Executa a negociação e validação estrita dos cabeçalhos da requisição HTTP.
     *
     * ### 1. O que faz
     * Inspeciona os cabeçalhos agrupados em [NegotiateHeaders], valida cada campo contra as regras de negócio
     * e produz o veredito definitivo de validação [ContextValidation].
     *
     * ### 2. Para que serve
     * Cria a instância imutável de [ClientContext] requerida pelas etapas subsequentes do pipeline de composição,
     * ou relata detalhadamente a lista de cabeçalhos que violaram o protocolo.
     *
     * ### 3. Como funciona
     * 1. Define uma função auxiliar interna para validar a presença de cabeçalhos obrigatórios e registrar violações com motivo `"required"`.
     * 2. Valida a versão de schema (`UI-Schema-Version`) quanto a formato e homologação em [MvpCatalog.SUPPORTED_SCHEMA_VERSIONS].
     * 3. Valida a plataforma (`Client-Platform`) via [ClientPlatform.parse].
     * 4. Valida a versão do cliente (`Client-Version`) via [SemVer.parseThreePart].
     * 5. Valida a compilação do cliente (`Client-Build`) via regex [BUILD].
     * 6. Extrai o idioma primário (`Accept-Language`) via [primaryLocale] e valida via regex [LOCALE].
     * 7. Valida a versão de API (`API-Version`), aceitando unicamente o valor `"1"`.
     * 8. Avalia o cabeçalho opcional de versão do SO (`OS-Version`) via [SemVer.parse].
     * 9. Se houver qualquer campo obrigatório nulo ou lista de violações não vazia, retorna [ContextValidation.Invalid].
     * 10. Caso contrário, retorna [ContextValidation.Valid] contendo a instância completa de [ClientContext].
     *
     * @param headers Conjunto bruto de cabeçalhos HTTP extraídos da requisição.
     * @return [ContextValidation.Valid] com o [ClientContext] gerado, ou [ContextValidation.Invalid] com as violações acumuladas.
     */
    fun negotiate(headers: NegotiateHeaders): ContextValidation {
        val violations = mutableListOf<ContextViolation>()

        fun requireHeader(name: String, value: String?): String? {
            if (value.isNullOrBlank()) {
                violations += ContextViolation(name, "required")
                return null
            }
            return value.trim()
        }

        val schema = requireHeader("UI-Schema-Version", headers.uiSchemaVersion)
        if (schema != null && !SCHEMA.matches(schema)) {
            violations += ContextViolation("UI-Schema-Version", "invalid")
        } else if (schema != null && schema !in MvpCatalog.SUPPORTED_SCHEMA_VERSIONS) {
            violations += ContextViolation("UI-Schema-Version", "unsupported")
        }

        val platformRaw = requireHeader("Client-Platform", headers.clientPlatform)
        val platform = ClientPlatform.parse(platformRaw)
        if (platformRaw != null && platform == null) {
            violations += ContextViolation("Client-Platform", "invalid")
        }

        val versionRaw = requireHeader("Client-Version", headers.clientVersion)
        val appVersion = SemVer.parseThreePart(versionRaw)
        if (versionRaw != null && appVersion == null) {
            violations += ContextViolation("Client-Version", "invalid")
        }

        val build = requireHeader("Client-Build", headers.clientBuild)
        if (build != null && !BUILD.matches(build)) {
            violations += ContextViolation("Client-Build", "invalid")
        }

        val language = requireHeader("Accept-Language", headers.acceptLanguage)
        val locale = language?.let { primaryLocale(it) }
        if (language != null && (locale == null || !LOCALE.matches(locale))) {
            violations += ContextViolation("Accept-Language", "invalid")
        }

        val apiVersion = requireHeader("API-Version", headers.apiVersion)
        if (apiVersion != null && apiVersion != "1") {
            violations += ContextViolation("API-Version", "unsupported")
        }

        val osRaw = headers.osVersion?.trim()?.takeIf { it.isNotEmpty() }
        val osVersion = osRaw?.let { SemVer.parse(it) }
        if (osRaw != null && osVersion == null) {
            violations += ContextViolation("OS-Version", "invalid")
        }

        if (platform == null || appVersion == null || build == null || schema == null || locale == null || apiVersion == null || violations.isNotEmpty()) {
            return ContextValidation.Invalid(violations)
        }

        return ContextValidation.Valid(
            ClientContext(
                platform = platform,
                appVersion = appVersion,
                build = build,
                osVersion = osVersion,
                osVersionRaw = osRaw ?: "",
                schemaVersion = schema,
                locale = locale,
                apiVersion = apiVersion,
                headerCapabilities = Capability.parseList(headers.componentCapabilities),
                channelHint = Channel.parse(headers.channel),
            ),
        )
    }

    /**
     * Extrai a tag de idioma primária do cabeçalho HTTP `Accept-Language`.
     *
     * ### 1. O que faz
     * Recupera o primeiro elemento de preferência de idioma da string, descartando alternativas secundárias e pesos de qualidade.
     *
     * ### 2. Para que serve
     * Simplifica a negociação de localidade do BFF, selecionando o idioma prioritário configurado no dispositivo do usuário.
     *
     * ### 3. Como funciona
     * Divide a string informada por vírgulas, obtém o primeiro item, remove eventuais fatores de ponderação (prefixo antes de `;`)
     * e retorna a string resultante sem espaços em branco, ou `null` caso vazia.
     *
     * @param acceptLanguage Valor bruto do cabeçalho `Accept-Language` (ex.: "pt-BR,pt;q=0.9,en-US;q=0.8").
     * @return Tag primária de localidade normalizada (ex.: "pt-BR") ou `null` se ausente/inválida.
     */
    private fun primaryLocale(acceptLanguage: String): String? {
        val primary = acceptLanguage.split(",").firstOrNull()?.trim()?.substringBefore(";")?.trim()
        return primary?.takeIf { it.isNotEmpty() }
    }
}
