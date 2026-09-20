package br.com.empresa.sdui.api.configuration

import br.com.empresa.sdui.api.mapping.ScreenResponseMapper
import br.com.empresa.sdui.api.trace.ComposeTraceContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

/** Beans da camada de borda. Separado da configuracao de adapters para a api nao depender deles. */
@Configuration
class ApiBeans {
    @Bean
    fun composeTraceContext(): ComposeTraceContext = ComposeTraceContext()

    @Bean
    fun screenResponseMapper(jsonMapper: JsonMapper): ScreenResponseMapper = ScreenResponseMapper(jsonMapper)
}
