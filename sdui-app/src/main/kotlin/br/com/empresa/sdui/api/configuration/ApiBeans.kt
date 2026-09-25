package br.com.empresa.sdui.api.configuration

import br.com.empresa.sdui.api.mapping.ScreenResponseMapper
import br.com.empresa.sdui.api.trace.ComposeTraceContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

/**
 * Fabrica de beans Spring da camada de apresentacao e borda HTTP da aplicacao SDUI.
 *
 * ### 1. O que faz
 * Instancia e disponibiliza no contexto de injecao de dependencias do Spring os componentes
 * utilitarios da camada de API, especificamente o contexto de trace e o mapeador de respostas.
 *
 * ### 2. Para que serve
 * Isola as definicoes de beans da camada de apresentacao (`api`) da configuracao de adaptadores (`adapters`),
 * garantindo estrito cumprimento das regras de dependencia e fronteiras de pacotes validadas via ArchUnit.
 *
 * ### 3. Como funciona
 * Classe anotada com [Configuration] contendo metodos produtores anotados com [Bean], gerenciados
 * com escopo singleton pelo Spring IoC container.
 */
@Configuration
class ApiBeans {

    /**
     * Cria e disponibiliza a instancia singleton de [ComposeTraceContext].
     *
     * ### 1. O que faz
     * Fabrica o gerenciador de contexto de diagnostico e rastreabilidade para composicao de telas.
     *
     * ### 2. Para que serve
     * Permite que controladores e filtros injetem e consultem o contexto atrelado a thread virtual corrente.
     *
     * ### 3. Como funciona
     * Instancia diretamente um novo [ComposeTraceContext] e o registra no container Spring.
     *
     * @return Nova instancia de [ComposeTraceContext].
     */
    @Bean
    fun composeTraceContext(): ComposeTraceContext = ComposeTraceContext()

    /**
     * Cria e disponibiliza a instancia singleton de [ScreenResponseMapper].
     *
     * ### 1. O que faz
     * Fabrica o componente responsavel por transformar entidades [br.com.empresa.sdui.core.model.ComposedScreen]
     * em respostas contratuais DTO.
     *
     * ### 2. Para que serve
     * Fornece o mapeador de projecao de resposta para controladores HTTP como o [br.com.empresa.sdui.api.http.SurfaceController].
     *
     * ### 3. Como funciona
     * Injeta a instancia compartilhada de [JsonMapper] Jackson 3 para permitir construcao eficiente de arvores de nos JSON.
     *
     * @param jsonMapper Mapper Jackson 3 configurado para o servico.
     * @return Instancia configurada de [ScreenResponseMapper].
     */
    @Bean
    fun screenResponseMapper(jsonMapper: JsonMapper): ScreenResponseMapper = ScreenResponseMapper(jsonMapper)
}
