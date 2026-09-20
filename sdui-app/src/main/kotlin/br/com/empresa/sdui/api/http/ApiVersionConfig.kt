package br.com.empresa.sdui.api.http

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Configura o versionamento por header API-Version: o eixo A de protocolo.
 *
 * Versao 1 e a unica suportada no MVP. O default registrado aqui vale para o roteamento do Spring,
 * mas nao dispensa o cliente de mandar o header: a negociacao exige API-Version presente e recusa
 * a requisicao com 400 quando ele falta. Declarar a versao desde o inicio e o que permite
 * introduzir uma versao 2 depois sem quebrar quem ja integrou.
 */
@Configuration
class ApiVersionConfig : WebMvcConfigurer {
    override fun configureApiVersioning(configurer: ApiVersionConfigurer) {
        configurer
            .useRequestHeader("API-Version")
            .addSupportedVersions("1")
            .setDefaultVersion("1")
    }
}
