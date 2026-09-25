package br.com.empresa.sdui.api.http

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Configuracao de versionamento de API HTTP via cabecalho padronizado (Eixo A de protocolo).
 *
 * ### 1. O que faz
 * Habilita e define as regras de versionamento de rotas HTTP do Spring Web MVC baseadas no cabecalho
 * `API-Version`, registrando o suporte a versao `1`.
 *
 * ### 2. Para que serve
 * Assegura conformidade com o Eixo A de compatibilidade do BFF Server-Driven UI (versao do protocolo HTTP),
 * permitindo que novas versoes da API REST coexistam futuramente sem romper contratos com clientes existentes.
 *
 * ### 3. Como funciona
 * Implementa [WebMvcConfigurer] e sobreescreve [configureApiVersioning], instruindo o Spring a inspecionar
 * o cabecalho `API-Version`, aceitar a versao `1` e defini-la como default de roteamento. A negociacao de
 * cabecalhos do caso de uso continua exigindo a presenca explicita do cabecalho na requisicao do cliente.
 */
@Configuration
class ApiVersionConfig : WebMvcConfigurer {

    /**
     * Configura o mecanismo de deteccao de versao da API no roteador Spring MVC.
     *
     * ### 1. O que faz
     * Declara o nome do cabecalho HTTP de versionamento, as versoes aceitas e a versao padrao do servico.
     *
     * ### 2. Para que serve
     * Ativa o roteamento por versao nas anotacoes de controladores (como `version = "1"` no [SurfaceController]).
     *
     * ### 3. Como funciona
     * Configura o [ApiVersionConfigurer] para:
     * 1. Utilizar o cabecalho de requisicao `API-Version` (`useRequestHeader`).
     * 2. Registrar a versao `1` na lista de versoes suportadas (`addSupportedVersions`).
     * 3. Definir a versao `1` como versao padrao caso nao especificada no roteamento (`setDefaultVersion`).
     *
     * @param configurer Objeto de configuracao de versionamento da API do Spring MVC.
     */
    override fun configureApiVersioning(configurer: ApiVersionConfigurer) {
        configurer
            .useRequestHeader("API-Version")
            .addSupportedVersions("1")
            .setDefaultVersion("1")
    }
}
