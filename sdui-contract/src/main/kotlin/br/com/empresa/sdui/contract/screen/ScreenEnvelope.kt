package br.com.empresa.sdui.contract.screen

import br.com.empresa.sdui.contract.analytics.ScreenAnalyticsResponse
import br.com.empresa.sdui.contract.client.ClientResponse
import br.com.empresa.sdui.contract.targeting.TargetingResponse

/**
 * Envelope de metadados, integridade, auditoria e telemetria da composição SDUI.
 *
 * ### 1. O que faz
 * Representa o envelope raiz de metadados operacionais retornado junto com a árvore de componentes Server-Driven UI.
 *
 * ### 2. Para que serve
 * Garante a rastreabilidade e governança ponta a ponta da composição servida aos aplicativos móveis (iOS e Android).
 * Transporta o validador de cache HTTP ([etag]), os identificadores de integridade estrutural ([skeletonHash]), o eco
 * dos três eixos de compatibilidade (Eixo A em [schemaVersion], Eixo B refletido em [omitted], Eixo C em [client] e
 * [targeting]), o estado de degradação da escada de fallback (`ADR-007` em [fallback] e [fallbackReason]) e a telemetria
 * unificada em [analytics].
 *
 * ### 3. Como funciona
 * O orquestrador central de composição (`ComposeScreenService`) monta este envelope ao concluir o pipeline de composição.
 * Caso ocorra acerto de cache (`treeCache`), os campos dependentes da requisição corrente ([client], [locale],
 * [generatedAt]) são reidratados dinamicamente via `withRequester`, assegurando que o envelope sempre audite a requisição
 * real sem reaproveitar o contexto do primeiro dispositivo que povoou o cache.
 *
 * @property surface Identificador canônico da surface renderizada (ex.: "home", "catalog").
 * @property platform Plataforma de destino do cliente móvel ("ios" ou "android").
 * @property schemaVersion Versão do protocolo de envelope e contrato SDUI (Eixo A, ex.: "3").
 * @property specRevisionId Identificador único e imutável da revisão de especificação que gerou a tela.
 * @property skeletonId Identificador do esqueleto de layout utilizado como gabarito estrutural da tela.
 * @property skeletonHash Checksum criptográfico (SHA-256) do esqueleto, garantindo integridade estrutural.
 * @property etag Token HTTP de integridade de payload para validação condicional com If-None-Match.
 * @property generatedAt Timestamp ISO-8601 em UTC indicando o instante exato em que a composição foi gerada.
 * @property locale Código de localidade e idioma efetivo utilizado na resolução dos textos (ex.: "pt-BR").
 * @property channel Canal de distribuição ou origem do aplicativo cliente (ex.: "app-store", "play-store").
 * @property fallback Flag booleana indicando se a resposta foi entregue sob degradação na escada de fallback.
 * @property fallbackReason Motivo técnico padronizado que justificou o acionamento do fallback.
 * @property omitted Lista de seções da especificação que foram omitidas da entrega final com seus respectivos motivos.
 * @property client Eco auditável dos dados e parâmetros de contexto informados pelo dispositivo solicitante.
 * @property targeting Faixa de versões de aplicativo e critérios de segmentação atendidos pela especificação.
 * @property analytics Atributos pré-calculados para emissão uniforme do evento de telemetria de tela pelo cliente.
 */
data class ScreenEnvelope(
    /**
     * Identificador canônico da surface renderizada.
     *
     * ### 1. O que faz
     * Identifica a superfície de interface servida (ex.: "home", "catalog").
     *
     * ### 2. Para que serve
     * Permite ao cliente móvel confirmar que a resposta recebida corresponde à surface solicitada no endpoint.
     *
     * ### 3. Como funciona
     * Ecoado a partir do path da requisição HTTP (`/v1/surfaces/{surface}`) após validação contra a allowlist de surfaces.
     */
    val surface: String,

    /**
     * Plataforma de destino do cliente móvel.
     *
     * ### 1. O que faz
     * Informa a plataforma operacional do dispositivo solicitante ("ios" ou "android").
     *
     * ### 2. Para que serve
     * Assegura o isolamento estrito entre plataformas: pointers de governança e chaves de cache são 100% independentes.
     *
     * ### 3. Como funciona
     * Ecoado a partir do cabeçalho obrigatório `Client-Platform` após validação no estágio de negociação.
     */
    val platform: String,

    /**
     * Versão do protocolo de envelope e contrato SDUI (Eixo A).
     *
     * ### 1. O que faz
     * Declara a versão principal do envelope e do protocolo de comunicação entre cliente e servidor.
     *
     * ### 2. Para que serve
     * Define a compatibilidade estrutural da árvore de UI. No MVP, fixa o contrato na versão "3".
     *
     * ### 3. Como funciona
     * Validado na negociação a partir do cabeçalho `UI-Schema-Version` e ecoado para garantir decodificação compatível no app.
     */
    val schemaVersion: String,

    /**
     * Identificador único e imutável da revisão de especificação.
     *
     * ### 1. O que faz
     * Transporta o hash ou UUID da revisão de spec publicada que originou a tela.
     *
     * ### 2. Para que serve
     * Permite auditoria determinística, correlação com o repositório de governança e rastreamento de erros no cliente.
     *
     * ### 3. Como funciona
     * Atribuído pelo estágio de seleção (`Select`) com base no pointer ativo (canary ou stable) e nos critérios de targeting.
     */
    val specRevisionId: String,

    /**
     * Identificador do esqueleto de layout da tela.
     *
     * ### 1. O que faz
     * Informa o identificador do esqueleto estrutural (ex.: "home.default") utilizado na composição.
     *
     * ### 2. Para que serve
     * Define o gabarito ordenado de slots estruturais nos quais as seções serão montadas pelo motor nativo.
     *
     * ### 3. Como funciona
     * Resgatado da spec de tela associada e vinculado aos slots presentes no payload.
     */
    val skeletonId: String,

    /**
     * Checksum criptográfico (SHA-256) do esqueleto estrutural.
     *
     * ### 1. O que faz
     * Transporta a assinatura SHA-256 do esqueleto para verificação de integridade estrutural.
     *
     * ### 2. Para que serve
     * Assegura que o esqueleto não foi adulterado ou alterado de forma não rastreada entre a publicação e a entrega.
     *
     * ### 3. Como funciona
     * Calculado a partir da ordenação canônica dos slots na governança e conferido imutável no envelope.
     */
    val skeletonHash: String,

    /**
     * Token HTTP de integridade de payload para validação de cache.
     *
     * ### 1. O que faz
     * Contém o hash ETag representativo da resposta gerada para o cliente.
     *
     * ### 2. Para que serve
     * Permite ao cliente móvel realizar requisições condicionais com `If-None-Match`, recebendo `304 Not Modified`.
     *
     * ### 3. Como funciona
     * Gerado a partir do hash do payload serializado e validado contra os cabeçalhos de requisição do cliente.
     */
    val etag: String,

    /**
     * Timestamp ISO-8601 em UTC do momento da composição.
     *
     * ### 1. O que faz
     * Registra o instante exato em que a resposta foi processada e empacotada pelo servidor.
     *
     * ### 2. Para que serve
     * Facilita a auditoria de frescor dos dados e cálculo de expiração de cache no dispositivo móvel.
     *
     * ### 3. Como funciona
     * Gerado no orquestrador via relógio UTC (`Instant.now()`), sendo reidratado a cada requisição mesmo em acertos de cache.
     */
    val generatedAt: String,

    /**
     * Código de localidade e idioma efetivo.
     *
     * ### 1. O que faz
     * Especifica a localidade adotada na resolução de textos e formatações da tela (ex.: "pt-BR").
     *
     * ### 2. Para que serve
     * Permite confirmar que o aplicativo recebeu strings regionalizadas conforme a preferência negociada.
     *
     * ### 3. Como funciona
     * Extraído do cabeçalho `Accept-Language` ou definido com o padrão corporativo brasileiro caso omitido.
     */
    val locale: String,

    /**
     * Canal de distribuição ou origem do aplicativo.
     *
     * ### 1. O que faz
     * Identifica o canal pelo qual o aplicativo foi distribuído (ex.: "app-store", "play-store").
     *
     * ### 2. Para que serve
     * Permite segmentar telemetria e aplicar políticas operacionais específicas por canal de distribuição.
     *
     * ### 3. Como funciona
     * Ecoado a partir do cabeçalho de contexto do cliente após sanitização no estágio de negociação.
     */
    val channel: String,

    /**
     * Flag booleana indicando acionamento da escada de fallback.
     *
     * ### 1. O que faz
     * Sinaliza com `true` se a tela foi servida a partir de mecanismos de degradação da escada de fallback (`ADR-007`).
     *
     * ### 2. Para que serve
     * Informa ao aplicativo e aos sistemas de monitoramento que a composição sofreu indisponibilidade parcial ou total.
     *
     * ### 3. Como funciona
     * Marcado como `false` em fluxo nominal; marcado como `true` caso recuperado de cache de emergência ("last good")
     * ou montado com omissão forçada de seções com falha.
     */
    val fallback: Boolean,

    /**
     * Motivo técnico do acionamento do fallback.
     *
     * ### 1. O que faz
     * Descreve o código padronizado que motivou o fallback (ex.: "NONE", "REDIS_UNAVAILABLE", "DEPENDENCY_TIMEOUT").
     *
     * ### 2. Para que serve
     * Permite diagnóstico rápido da causa raiz da degradação sem vazar stack traces ou dados sensíveis aos clientes.
     *
     * ### 3. Como funciona
     * Definido pelo orquestrador de resiliência e fallback conforme o ponto de falha interceptado durante o pipeline.
     */
    val fallbackReason: String,

    /**
     * Lista de seções omitidas da composição final.
     *
     * ### 1. O que faz
     * Relaciona todas as seções que constavam na especificação publicada mas não puderam ser entregues.
     *
     * ### 2. Para que serve
     * Permite ao cliente nativo e aos times de suporte entender exatamente por que um bloco não apareceu na tela.
     *
     * ### 3. Como funciona
     * Preenchido pelo estágio de filtro de capabilities (Eixo B) e pelo coordenador de hidratação sob falha tolerada.
     */
    val omitted: List<OmittedItemResponse> = emptyList(),

    /**
     * Eco auditável dos dados declarados pelo cliente na requisição.
     *
     * ### 1. O que faz
     * Agrupa os cabeçalhos de contexto enviados pelo aplicativo solicitante.
     *
     * ### 2. Para que serve
     * Fornece auditabilidade imediata para conferir por que o servidor tomou determinadas decisões de targeting e filtro.
     *
     * ### 3. Como funciona
     * Encapsula uma instância de [ClientResponse], reidratada no envelope a cada atendimento de requisição.
     */
    val client: ClientResponse,

    /**
     * Faixa de versões e segmentação atendidas pela especificação.
     *
     * ### 1. O que faz
     * Detalha os critérios de targeting (Eixo C) configurados na spec que atendeu a requisição.
     *
     * ### 2. Para que serve
     * Justifica a seleção da spec para o dispositivo cliente, demonstrando a faixa de versões de app e SO elegíveis.
     *
     * ### 3. Como funciona
     * Encapsula [TargetingResponse] extraído diretamente das configurações da spec selecionada.
     */
    val targeting: TargetingResponse,

    /**
     * Atributos pré-calculados para telemetria de visualização da tela.
     *
     * ### 1. O que faz
     * Fornece o evento analítico de tela formatado com todas as dimensões de contexto necessárias.
     *
     * ### 2. Para que serve
     * Padroniza o disparo de métricas de visualização entre iOS e Android, evitando discrepâncias nos relatórios corporativos.
     *
     * ### 3. Como funciona
     * Encapsula [ScreenAnalyticsResponse], pré-montado no backend e disparado pelo dispatcher analítico nativo.
     */
    val analytics: ScreenAnalyticsResponse,
)
