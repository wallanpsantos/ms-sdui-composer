# Pré-arquitetura modular — ms-sdui-composer

**ms-sdui-composer** = o serviço que, a cada request, compõe a árvore de UI da surface a partir de uma spec versionada,
do contexto do cliente e das capabilities, devolvendo um envelope pronto e seguro para o app.

Este documento é a pré-arquitetura canônica. O bootstrap e a H00 já estão concluídos. A implementação produtiva de
`H01`–`H18` segue este desenho de forma direta e completa, sem ciclos repetitivos de Gradle nem espera de testes.

## Kotlin, Gradle, Clean Architecture pragmática e estratégia de testes

**Baseline:** Kotlin 2.4.20 sobre JVM Java 25 LTS, Spring Boot 4.1.1 (que gerencia Spring Framework 7.0.x), Gradle 9.7.1
com Kotlin DSL, multi-projeto.

**Objetivo:** estruturar o **ms-sdui-composer** para suportar composição de surfaces por múltiplos times, evolução de
telas, hidratação dinâmica por squads e testes com níveis claros de isolamento (tendo a `home` como primeira surface).

> **Atualização tecnológica — 18/09/2026.** Esta revisão migra o documento de Maven/Java para Gradle/Kotlin e fecha o
> ADR-001 em **quatro módulos de produção** (`sdui-contract`, `sdui-core`, `sdui-app`, `sdui-bootstrap`) mais o módulo
> de
> teste `sdui-integration-test`.
>
> O gerenciamento de versões é centralizado no BOM do Spring Boot, importado como `platform(...)` nos convention
> plugins.
> **Não fixar** no build versões de Spring Framework, starters, Jackson, JUnit Jupiter, AssertJ, Mockito,
> Testcontainers,
> driver MongoDB ou Lettuce. Bibliotecas fora do BOM (ArchUnit 1.5.0, MockK, springmockk, WireMock) têm versão explícita
> em `gradle/libs.versions.toml`, verificada antes de entrar.
>
> O projeto permanece sem Kafka e sem Spring Cloud por padrão. Kafka exige fluxo assíncrono real com contrato,
> consumidores, idempotência, retry, DLQ, retenção e owner operacional. Spring Cloud só entra com caso concreto de
> Config
> Client, Discovery, client-side LoadBalancer ou Circuit Breaker. MongoDB permanece porque esta pré-arquitetura o define
> como store de specs; RestClient/WireMock continuam condicionais à existência de hidratação HTTP real.

---

## 1. Decisão arquitetural

O serviço é um **build Gradle multi-projeto** (Kotlin DSL), com `settings.gradle.kts` na raiz, version catalog em
`gradle/libs.versions.toml` e convention plugins em `build-logic/`. O único artefato executável é `sdui-bootstrap`; os
demais produzem JARs internos.

Toda a produção é escrita em **Kotlin** (`src/main/kotlin`), e todos os testes também (`src/test/kotlin`). Não há fontes
Java, salvo exigência comprovada de integração externa.

A organização segue uma **Clean Architecture pragmática**, com fronteiras em dois níveis:

| Fronteira                                  | Mecanismo                             | Por quê                                                     |
|--------------------------------------------|---------------------------------------|-------------------------------------------------------------|
| Pureza do domínio (`sdui-core`)            | Módulo Gradle + `verifyPureClasspath` | É onde Spring e infraestrutura vazam com mais facilidade    |
| Contrato público (`sdui-contract`)         | Módulo Gradle                         | Única fronteira que outro processo pode consumir            |
| Executável (`sdui-bootstrap`)              | Módulo Gradle                         | Só um lugar tem `@SpringBootApplication` e wiring final     |
| orchestrator × adapters × api (`sdui-app`) | Pacotes + ArchUnit                    | Um engenheiro, sem ownership separado; fronteira por pacote |

Camadas lógicas:

- **core** (`sdui-core`): regras puras de domínio; não depende de Spring, MongoDB, Redis, HTTP nem Jackson.
- **orchestrator** (pacote em `sdui-app`): casos de uso, portas e o pipeline de composição; sem Spring.
- **contract** (`sdui-contract`): DTOs do contrato REST/JSON e catálogo público do SDUI.
- **adapters** (pacote em `sdui-app`): persistência, cache e clients HTTP.
- **api** (pacote em `sdui-app`): entrada HTTP/MVC e conversão entre HTTP e casos de uso.
- **bootstrap** (`sdui-bootstrap`): monta a aplicação, configura propriedades, segurança, observabilidade e beans.

A proposta não introduz Hexagonal Architecture como framework adicional. Portas/interfaces existem só onde o caso de uso
realmente precisa: stores, cache, singleflight, unidade transacional e hydrators.

Também não são introduzidos CQRS, Event Sourcing, command bus ou uma camada `common` genérica. O projeto é um
composer/BFF de UI stateless, não um sistema de domínio transacional.

O plano do projeto define que o MS entrega uma árvore de UI hidratada, não acessa contratos, apólices ou domínios de
negócio diretamente, usa MongoDB como fonte de specs e Redis para árvore, spec, projeções, `lastgood` e singleflight.
Ele também proíbe N+1 por widget e exige que uma section degradada possa ser omitida sem derrubar a Home
(`plano-servico-sdui.md`).

> **Decisões fechadas:** as escolhas que divergem do `plano-servico-sdui.md` ou que não estavam explícitas nele estão
> registradas como ADRs no **§16**. Toda divergência em relação ao plano precisa de um ADR antes de virar código.

---

## 2. Estrutura de módulos

```text
ms-sdui-composer/
├── settings.gradle.kts
├── build.gradle.kts                 (vazio ou apenas `apply false`)
├── gradle.properties
├── README.md
├── gradlew
├── gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
│
├── build-logic/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── sdui.kotlin-library.gradle.kts
│       ├── sdui.spring-library.gradle.kts
│       └── sdui.spring-app.gradle.kts
│
├── sdui-contract/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/br/com/empresa/sdui/contract/
│       └── test/kotlin/br/com/empresa/sdui/contract/
│
├── sdui-core/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/br/com/empresa/sdui/core/
│       └── test/kotlin/br/com/empresa/sdui/core/
│
├── sdui-app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/br/com/empresa/sdui/
│       │   ├── orchestrator/
│       │   ├── adapters/
│       │   │   ├── mongo/
│       │   │   ├── redis/
│       │   │   └── http/          (somente quando houver hidratação HTTP real)
│       │   └── api/
│       └── test/kotlin/br/com/empresa/sdui/
│           ├── orchestrator/
│           ├── adapters/
│           └── api/
│
├── sdui-bootstrap/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/br/com/empresa/sdui/bootstrap/
│       ├── main/resources/
│       │   ├── application.yaml
│       │   ├── application-local.yaml
│       │   └── application-test.yaml
│       └── test/kotlin/br/com/empresa/sdui/bootstrap/
│
└── sdui-integration-test/
    ├── build.gradle.kts
    └── src/test/kotlin/br/com/empresa/sdui/it/
```

O pacote base é `br.com.empresa.sdui`. Cada camada vive em `br.com.empresa.sdui.<camada>`
(`core`, `contract`, `orchestrator`, `adapters`, `api`, `bootstrap`), inclusive as três que compartilham o módulo
`sdui-app`. Isso mantém as regras ArchUnit estáveis e torna mecânica uma futura separação de `sdui-app` em módulos.

### Papel dos módulos

| Módulo                  | Camadas                       |            Spring em produção? | Executável? |
|-------------------------|-------------------------------|-------------------------------:|------------:|
| `sdui-contract`         | contract                      |                            Não |         Não |
| `sdui-core`             | core                          |                            Não |         Não |
| `sdui-app`              | orchestrator + adapters + api | Sim (exceto em `orchestrator`) |         Não |
| `sdui-bootstrap`        | bootstrap                     |                            Sim |         Sim |
| `sdui-integration-test` | testes HTTP/infra e ArchUnit  |                Apenas em teste |         Não |

### Quando dividir `sdui-app`

Separar `sdui-app` em `sdui-orchestrator`, `sdui-adapters` e `sdui-api` (ou adapters por tecnologia) é uma evolução
mecânica, porque os pacotes já seguem a fronteira. Gatilhos: ownership distinto por squad, ciclo de deploy distinto, ou
uma regra ArchUnit que precise ser violada repetidamente para o código compilar. Não dividir por simetria.

O mesmo vale para `sdui-contract`: é uma fronteira pública do processo, não um lugar para acumular qualquer DTO interno.

---

## 3. Grafo de dependências

```text
                    +----------------------+
                    |    sdui-bootstrap    |
                    | Spring Boot runnable |
                    +----------+-----------+
                               |
                    +----------v-----------------------------+
                    |               sdui-app                 |
                    |  api ─────────► orchestrator ◄── adapters
                    |   │                  │                 |
                    +---│------------------│-----------------+
                        │                  │
               +--------v-------+  +-------v--------+
               | sdui-contract  |  |   sdui-core    |
               | DTOs públicos  |  | domínio puro   |
               +----------------+  +----------------+

Setas internas de sdui-app são regras ArchUnit, não dependências Gradle.
sdui-integration-test depende de sdui-bootstrap (e dos demais, em teste, para ArchUnit).
```

### Declaração no Gradle

| Módulo                  | Dependências de projeto                                                            | Observação                                                                       |
|-------------------------|------------------------------------------------------------------------------------|----------------------------------------------------------------------------------|
| `sdui-core`             | nenhuma                                                                            | stdlib Kotlin apenas; `verifyPureClasspath` ativo                                |
| `sdui-contract`         | nenhuma                                                                            | Jackson somente para contrato/serialização; `verifyPureClasspath` ativo          |
| `sdui-app`              | `api(project(":sdui-core"))`, `implementation(project(":sdui-contract"))`          | `api` porque portas e casos de uso expõem tipos do core nas assinaturas públicas |
| `sdui-bootstrap`        | `implementation(project(":sdui-app"))`                                             | recebe `sdui-core` transitivamente; não enxerga `sdui-contract` diretamente      |
| `sdui-integration-test` | `testImplementation` de `sdui-bootstrap`, `sdui-app`, `sdui-core`, `sdui-contract` | nada em `main`                                                                   |

### Regra importante sobre o `contract`

O `sdui-core` não depende de `sdui-contract`, e isso é garantido pelo Gradle: não há declaração de dependência e o
classpath do core não contém o contrato. Dentro de `sdui-app`, o pacote `orchestrator` também não pode depender de
`contract` — essa regra é do ArchUnit. Só `api` converte entre modelos do core/orchestrator e DTOs do contrato.

Essa separação evita que o domínio fique preso a nomes JSON, annotations de Jackson, envelopes HTTP e decisões de
compatibilidade externa.

---

## 4. Modelo de Clean Architecture

### 4.1 `sdui-core`: Domain

```text
br.com.empresa.sdui.core
├── context/
│   ├── ClientContext.kt
│   ├── ClientPlatform.kt
│   ├── ClientVersion.kt
│   ├── ClientBuild.kt
│   ├── OsVersion.kt
│   ├── UiSchemaVersion.kt
│   ├── Channel.kt
│   └── ComponentCapability.kt
├── model/
│   ├── Screen.kt
│   ├── Section.kt
│   ├── Action.kt
│   ├── Skeleton.kt
│   ├── Slot.kt
│   ├── Spec.kt
│   ├── SpecRevisionId.kt
│   ├── Pointer.kt
│   └── HydratedScreen.kt
├── policy/
│   ├── CapabilityCompatibilityPolicy.kt
│   ├── SpecSelectionPolicy.kt
│   ├── OmissionPolicy.kt
│   └── FallbackPolicy.kt
├── result/
│   ├── ContextValidation.kt
│   ├── SelectionResult.kt
│   ├── OmittedSection.kt
│   └── FallbackReason.kt
└── error/
    └── InvariantViolation.kt
```

O core é **Kotlin puro**: sem Spring, Mongo, Redis, HTTP, Jakarta ou Jackson. Usa apenas a stdlib Kotlin e `java.time`.

Convenções de modelagem:

- `data class` para entidades imutáveis e resultados; `sealed interface` para hierarquias fechadas; `enum class` para
  conjuntos estáveis (`ClientPlatform`, `Channel`).
- `@JvmInline value class` para identificadores e versões (`ClientVersion`, `ClientBuild`, `OsVersion`,
  `UiSchemaVersion`, `SpecRevisionId`), com validação no `init`. Value classes circulam apenas entre core e
  orchestrator; adapters e contract convertem para tipos primitivos, porque o suporte de Jackson e Spring Data a value
  classes exige cuidado e não deve vazar para as bordas.
- Resultado esperado não é exceção. Validação de contexto devolve um resultado selado:

```kotlin
sealed interface ContextValidation {
    data class Valid(val context: ClientContext) : ContextValidation
    data class Invalid(val violations: List<ContextViolation>) : ContextValidation
}
```

- Exceção fica reservada a violação de invariante (`InvariantViolation`), ou seja, bug — nunca a fluxo de negócio.
- Sem `!!`, sem `Optional`; ausência é `T?` somente quando for válida no domínio.

`Fragment`, `FragmentResolutionPolicy` e `OmittedFragment` **não** fazem parte do core no MVP (ADR-004).

### 4.2 `orchestrator` (em `sdui-app`): Application

```text
br.com.empresa.sdui.orchestrator
├── compose/
│   ├── ComposeScreenService.kt
│   ├── ComposeRequest.kt
│   ├── ComposeResult.kt
│   ├── ContextValidator.kt
│   ├── SpecResolver.kt
│   ├── CapabilityFilter.kt
│   ├── HydrationCoordinator.kt
│   ├── FallbackResolver.kt
│   └── ResponseAssembler.kt
├── port/
│   ├── inbound/
│   │   ├── ComposeScreenUseCase.kt
│   │   ├── PublishSpecUseCase.kt
│   │   └── RollbackPointerUseCase.kt
│   └── outbound/
│       ├── SpecStore.kt
│       ├── PointerStore.kt
│       ├── HydratedScreenCache.kt
│       ├── LastGoodScreenStore.kt
│       ├── ProjectionStore.kt
│       ├── ComposeSingleflight.kt
│       ├── AuditLogStore.kt
│       ├── IdempotencyStore.kt
│       └── TransactionalUnitOfWork.kt
├── hydration/
│   ├── SectionHydrator.kt
│   ├── HydrationContext.kt
│   ├── HydrationResult.kt
│   └── HydratorRegistry.kt
└── support/
    └── MetricsRecorder.kt
```

O orchestrator é Kotlin sem Spring, mesmo compartilhando o módulo com adapters e api: nenhuma annotation Spring, nenhum
import `org.springframework..` (regra ArchUnit). Suas classes são instanciadas por `@Bean` no bootstrap. Tempo é
injetado como `java.time.Clock`; não criar `ClockProvider` próprio.

Os pacotes de portas se chamam `port.inbound` e `port.outbound` (e não `port.in`/`port.out`) porque `in` é palavra
reservada em Kotlin e exigiria crases em todo `package` e `import`.

#### SPI `SectionHydrator`

A SPI para hydrators de squads é definida pelo orquestrador, não pelo adapter HTTP:

```kotlin
interface SectionHydrator {

    fun supports(type: ComponentType, typeVersion: Int): Boolean

    fun hydrate(context: HydrationContext, section: Section): HydrationResult
}
```

A SPI é **bloqueante** (sem `suspend`); ver ADR-012.

Regras importantes:

- O orchestrator não conhece cada squad.
- Cada hydrator declara os tipos que suporta.
- A hidratação é bounded, com timeout e tratamento terminal.
- Falha de uma hidratação pode omitir a section, conforme contrato.
- Nenhuma squad faz acesso irrestrito ao Redis ou Mongo.
- O hydrator não insere cor, tipografia, padding, tamanho, raio ou tokens de aparência no payload.
- O registro de hydrators é determinístico quando houver mais de um candidato para o mesmo `type@version`.

Se hydrators virarem artefatos externos no futuro, a SPI deve sair para um módulo pequeno e estável
(`sdui-hydrator-spi`), em vez de forçar squads a depender de `sdui-app`.

#### Porta `TransactionalUnitOfWork`

```kotlin
interface TransactionalUnitOfWork {
    fun <T> execute(work: () -> T): T
}
```

Ver ADR-003 (decisão vigente) e ADR-013 (alternativa proposta).

### 4.3 `sdui-contract`: contrato

```text
br.com.empresa.sdui.contract
├── screen/
│   ├── ScreenResponse.kt
│   ├── ScreenEnvelope.kt
│   ├── SkeletonResponse.kt
│   ├── SectionResponse.kt
│   └── OmittedItemResponse.kt
├── component/
│   ├── ComponentResponse.kt
│   ├── ActionResponse.kt
│   └── ActionPayload.kt
├── client/
│   └── ClientResponse.kt
├── admin/
│   ├── PublishSpecRequest.kt
│   └── RollbackRequest.kt
└── error/
    └── ApiErrorResponse.kt
```

DTOs são `data class` com propriedades `val`, sem lógica. Os nomes de campo Kotlin são os nomes JSON; `@JsonProperty`
só quando o nome JSON não puder ser um identificador Kotlin idiomático.

#### Sobre `@JsonTypeInfo`

Contratos polimórficos podem fazer sentido para os dados de section, mas não são adicionados por padrão.

A recomendação é um envelope estável e semântico:

```json
{
  "id": "sec_accounts",
  "type": "account_card",
  "typeVersion": 1,
  "data": {
    "title": "Conta",
    "rows": []
  },
  "actions": []
}
```

`@JsonTypeInfo` só é adequado se houver necessidade real de desserializar subclasses conhecidas no servidor. Para um
servidor que principalmente **produz** JSON, um `type` explícito e um `JsonNode` (Jackson 3,
`tools.jackson.databind.JsonNode`) validado é mais simples e menos acoplado.

Evitar usar o valor de `type` como nome de classe. O contrato é semanticamente versionado (`account_card@1`), não uma
hierarquia polimórfica exposta. Hierarquias `sealed` do core **não** são expostas no contrato.

O contrato respeita a tríade do projeto:

- **Sections:** blocos autocontidos com `id`, `type`, `typeVersion` e data.
- **Screens:** composição ordenada de sections em uma surface.
- **Actions:** intenções serializadas encaminhadas por dispatcher central do cliente.

### 4.4 `adapters` (em `sdui-app`): Infrastructure

```text
br.com.empresa.sdui.adapters
├── mongo/
│   ├── document/
│   ├── repository/
│   ├── mapper/
│   ├── store/
│   ├── tx/
│   └── configuration/
├── redis/
│   ├── key/
│   ├── codec/
│   ├── store/
│   ├── script/
│   └── configuration/
└── http/                (somente quando houver hidratação HTTP real)
    ├── client/
    ├── dto/
    ├── mapper/
    ├── hydrator/
    └── configuration/
```

Mapeamento entre camadas é feito com **funções de extensão Kotlin** (`fun SpecDocument.toDomain(): Spec`,
`fun Spec.toDocument(): SpecDocument`), explícitas e testáveis. Sem MapStruct e sem `kapt`.

#### Mongo

Responsável por:

- Specs publicadas e imutáveis.
- Catálogo de componentes.
- Skeletons.
- Pointers.
- Publish requests.
- Diffs.
- Audit log append-only.
- Idempotency records.

`@Document` e repositories Spring Data ficam apenas em `..adapters.mongo..`. Documentos são `data class` com `val`,
instanciados pelo construtor pelo Spring Data (que depende de `kotlin-reflect`). `pointers` usa `@Version` para
compare-and-set, o que também cobre o caminho sem transação multi-documento previsto no ADR-003.

A representação de UUID precisa ser explícita no Boot 4 (`spring.mongodb.representation.uuid`, §10); o Spring Data
MongoDB 5 não define mais um default.

O orchestrator recebe objetos do core por meio das portas de `port.outbound`.

#### Redis

Responsável por:

- Spec materializada.
- Screen hidratada (`tree`).
- Projeções de section.
- `lastgood`.
- Singleflight distribuído.
- Rate limit distribuído, se essa implementação for escolhida.

TTL da árvore e do `lastgood` são separados:

```text
sdui:tree:{surface}:{platform}:{schema}:{appMajorMinor}:{capsHash}:{channel}
  TTL curto, por exemplo 30–90 s

sdui:lastgood:{surface}:{platform}:{channel}
  TTL longo, conforme política de fallback
```

A chave `sdui:fragment:static:{...}` não existe no MVP (ADR-004).

Não colocar `userId`, PII, mídia binária ou credenciais em chaves/valores de cache compartilhado. O codec usa o mesmo
`JsonMapper` (Jackson 3) configurado pelo Boot.

#### HTTP clients

Usar clients HTTP somente se existir hidratação dinâmica real. Não adicionar WireMock, `RestClient` ou `@HttpExchange`
por antecipação.

Quando houver client HTTP, `RestClient` + `@HttpExchange` é adequado para este serviço síncrono: o Spring Framework
oferece `RestClient` como API síncrona e `HttpServiceProxyFactory` para criar proxies a partir de interfaces anotadas
com `@HttpExchange`.

O client deve possuir:

- Connect timeout.
- Read/response timeout.
- Tratamento de status e corpo de erro.
- Propagação de correlation/trace context conforme padrão da empresa.
- Limite de resposta.
- Retry somente para erros comprovadamente transitórios e idempotentes.
- Circuit breaker somente se telemetria justificar.
- Tratamento terminal de toda execução concorrente.

Exemplo de interface de client, definida no pacote do hydrator e implementada no adapter:

```kotlin
fun interface CoverageHydrationClient {
    fun fetch(request: CoverageHydrationRequest): CoverageProjection
}
```

O `SectionHydrator` usa a interface; a implementação concreta usa `RestClient` ou `@HttpExchange`. Assim, o teste do
orchestrator usa MockK (ou um fake) e o teste do adapter usa WireMock.

### 4.5 `api` (em `sdui-app`): Interface Adapters

```text
br.com.empresa.sdui.api
├── compose/
│   ├── ScreenController.kt
│   ├── RequestHeaders.kt
│   └── ResponseMapper.kt
├── admin/
│   ├── SpecAdminController.kt
│   ├── PublishAdminController.kt
│   └── PointerAdminController.kt
├── validation/
│   ├── ClientHeadersValidator.kt
│   └── ApiVersionValidator.kt
├── advice/
│   └── ApiExceptionHandler.kt
├── interceptor/
│   ├── CorrelationIdInterceptor.kt
│   └── RateLimitInterceptor.kt
├── context/
│   └── ComposeTraceContext.kt
└── security/
    └── AdminAuthorization.kt
```

O controller só deve:

1. Ler headers.
2. Validar formato HTTP e encaminhar para a aplicação.
3. Chamar o caso de uso.
4. Mapear resultado para DTO do contrato (`ResponseMapper`, funções de extensão).
5. Aplicar `ETag` e decidir 200/304.

Ele não acessa banco/cache, não executa hidratação e não implementa seleção de spec.

`FragmentController` não existe no MVP (ADR-004).

### 4.6 `sdui-bootstrap`

```text
br.com.empresa.sdui.bootstrap
├── SduiApplication.kt
├── configuration/
│   ├── ApplicationWiringConfiguration.kt
│   ├── AdapterConfiguration.kt
│   ├── HydratorConfiguration.kt
│   ├── WebConfiguration.kt
│   ├── SecurityConfiguration.kt
│   └── ObservabilityConfiguration.kt
└── properties/
    ├── ComposeProperties.kt
    ├── CacheProperties.kt
    ├── HydrationProperties.kt
    └── RateLimitProperties.kt
```

É o único módulo que contém `@SpringBootApplication` e faz o wiring final. As classes do orchestrator viram beans aqui,
via `@Bean` em `ApplicationWiringConfiguration`, sem annotations no próprio orchestrator.

```kotlin
@SpringBootApplication
@ConfigurationPropertiesScan
class SduiApplication

fun main(args: Array<String>) {
    runApplication<SduiApplication>(*args)
}
```

---

## 5. Fragmentos estáticos e telas dinâmicas

> **Status: proposta, fora do MVP (ADR-004).** "Fragment" não existe no vocabulário do `plano-servico-sdui.md` §2 e
> nenhuma fonte do projeto o define. Esta seção é **leitura de proposta, não especificação**: nenhuma classe, porta,
> store, controller, endpoint, chave Redis, propriedade YAML, métrica ou teste de fragment entra no MVP. Reabrir só
> quando existir uma segunda surface reusando o mesmo bloco.

A ideia de fragmentos estáticos e dinâmicos faz sentido, mas a separação precisa ser feita por **semântica e política de
cache**, não por classes que bypassam o pipeline.

### 5.1 Fragmento estático

Exemplos:

- Header padrão.
- Footer/chrome de uma surface.
- Skeleton estável.
- Bloco de navegação comum.

Características:

- Pode ser publicado com revisão.
- Pode ser materializado antecipadamente.
- Possui TTL de cache maior ou invalidação dirigida por revision.
- Não possui dados específicos de usuário.
- Ainda passa por validação de schema/capabilities antes de ser anexado à Screen.

### 5.2 Fragmento dinâmico

Exemplos:

- Cards cuja `data` depende de uma projeção externa.
- Ofertas vindas de API de squad.
- Conteúdo contextual que precisa de hidratação.

Características:

- Hidratação por `SectionHydrator`.
- Timeout individual e orçamento total do compose.
- Falha pode resultar em omissão de section.
- Dados precisam ser classificados para LGPD antes de entrar em cache.
- Não executar chamadas independentes ilimitadas para cada widget.

### 5.3 Resolução de fragmentos

O core pode definir uma política de resolução:

```text
ScreenSpec
  -> declara slots obrigatórios/opcionais
  -> declara fragment IDs e sections
  -> resolve fragmento estático publicado
  -> aplica overlay/plataforma
  -> filtra capabilities
  -> hidrata somente sections dinâmicas permitidas
  -> omite incompatíveis/falhas toleráveis
```

A herança não deve virar herança arbitrária de JSON. Prefira composição explícita:

```json
{
  "surface": "home",
  "fragments": [
    {
      "id": "home.header.default",
      "required": true
    },
    {
      "id": "home.footer.default",
      "required": false
    }
  ],
  "placements": []
}
```

Se for necessário override, definir uma operação fechada e validada no publish. Não fazer merge recursivo genérico de
qualquer JSON no request quente.

---

## 6. Estratégia de testes

Testes são escritos em Kotlin, com JUnit Jupiter e AssertJ na versão do BOM. **Testcontainers não entra em teste
unitário**: pertence aos testes de adapter e de integração.

### 6.1 Matriz de testes

| Camada                   | Ferramentas                                         | Módulo                  | Escopo                                          |                Infra real? |
|--------------------------|-----------------------------------------------------|-------------------------|-------------------------------------------------|---------------------------:|
| Unitário core            | JUnit Jupiter + AssertJ                             | `sdui-core`             | Regras, políticas e invariantes                 |                        Não |
| Unitário orchestrator    | JUnit Jupiter + AssertJ + MockK/fakes               | `sdui-app`              | Casos de uso, ordem de chamadas e falhas        |                        Não |
| Adapter Mongo/Redis      | JUnit Jupiter + Testcontainers                      | `sdui-app`              | Mapeamento, TTL, índices, comandos e integração |                        Sim |
| Client HTTP              | JUnit Jupiter + WireMock                            | `sdui-app`              | Status, timeout, payload, retry e erro remoto   | Simulado por servidor HTTP |
| API MVC                  | `@WebMvcTest` + MockMvc (DSL Kotlin) + `@MockkBean` | `sdui-app`              | Controller, headers, status, JSON e advice      |                        Não |
| Serialização do contrato | `JsonMapper` autoconfigurado pelo Boot              | `sdui-app`              | JSON de fio exatamente como sai em produção     |                        Não |
| Contexto                 | `@SpringBootTest`                                   | `sdui-bootstrap`        | Wiring sobe                                     |                        Não |
| Arquitetura              | ArchUnit (`ClassFileImporter`)                      | `sdui-integration-test` | Dependências entre pacotes e camadas            |                        Não |
| Integração/E2E leve      | `@SpringBootTest` + Testcontainers                  | `sdui-integration-test` | Fluxo HTTP com Mongo + Redis                    |                        Sim |
| Carga/performance        | Ferramenta de carga da plataforma                   | fora do build           | P99, payload, cache, singleflight               |          Ambiente dedicado |

`@WebMvcTest` limita o contexto aos componentes MVC e auto-configura MockMvc. No Boot 4, o suporte vem do starter
`spring-boot-starter-webmvc-test`; os pacotes das annotations de teste mudaram com a modularização do Boot 4, então os
imports devem ser os desse starter, nunca os da linha 3.x. `MockMvcTester`, baseado em AssertJ, também pode ser usado.

**Configuração de teste em `sdui-app`.** Slices como `@WebMvcTest` e `@DataMongoTest` procuram uma
`@SpringBootConfiguration` subindo a árvore de pacotes. Como `@SpringBootApplication` só existe em `sdui-bootstrap`,
`sdui-app` precisa de uma classe **somente de teste** em `src/test/kotlin/br/com/empresa/sdui/`:

```kotlin
@SpringBootConfiguration
@EnableAutoConfiguration
class SduiAppTestConfiguration
```

Ela não usa `@SpringBootApplication` e fica fora do escopo das regras ArchUnit, que importam apenas classes de produção.

### 6.2 Unitários: `sdui-core` e `orchestrator`

MockK é reservado para dependências externas do caso de uso. Para portas de store, **preferir fakes em memória** a
mocks: são mais legíveis, testam comportamento em vez de interação e sobrevivem a refactor. Não mockar `data class`,
value classes ou políticas puras.

Mockito não é usado. Se algum dia for necessário, entra com `mockito-kotlin` e agente configurado na task de teste.

#### Testes do `sdui-core`

- `ClientContext` válido e inválido (resultado `ContextValidation`, não exceção).
- Comparação semver inclusiva de `min`/`max`, não lexicográfica.
- `schemaVersion` compatível/incompatível.
- União da matriz de capabilities com delta do header.
- Omissão de `type@version` desconhecido.
- Slot `required` não pode ficar vazio (ADR-009).
- Ações permitidas e payloads fechados (ADR-011).
- Rejeição de campos de aparência e de seleção de variação de renderização no catálogo (ADR-010).

#### Testes do `orchestrator`

- Cache hit não acessa `SpecStore` nem hydrator.
- Cache miss consulta pointer e spec na ordem esperada.
- Apenas um líder de singleflight executa a composição.
- Falha de hydrator omite section quando permitido.
- Falha de hydrator em section de slot `required` aciona fallback.
- Timeout de hydrator **cancela** a tarefa (nenhuma tarefa órfã; ver §11).
- Falta de spec compatível usa `lastgood`.
- Fallback contém `fallback=true` e motivo correto.
- Árvore válida grava cache e lastgood.
- Capabilities incompatíveis não causam 4xx.
- Sem árvore e sem `lastgood` produz o resultado que a api traduz em `503` (ADR-007).

Exemplo de teste de caso de uso:

```kotlin
class ComposeScreenServiceTest {

    private val specStore = InMemorySpecStore()
    private val cache = InMemoryHydratedScreenCache()
    private val lastGood = InMemoryLastGoodScreenStore()
    private val singleflight = mockk<ComposeSingleflight>()

    @Test
    fun `usa lastgood quando nao ha spec compativel`() {
        // Arrange: cache miss, nenhuma spec aplicável, lastgood disponível.
        // Act: executa o caso de uso.
        // Assert: fallback=true e motivo NoCompatibleSpec.
    }
}
```

Nomes de teste usam crases e frases em português, sem acentos para evitar problemas de encoding em relatórios.

### 6.3 Adapter Mongo

Usar Testcontainers para validar o comportamento contra Mongo real:

- Mapeamento `Document <-> domain` (funções de extensão).
- Índices de specs, pointers e audit log.
- Query de candidatas por plataforma/status.
- Semver ordinal na query + confirmação em Kotlin.
- Imutabilidade de spec PUBLISHED.
- Transação de approve/rollback em replica set (o container de MongoDB do Testcontainers sobe como replica set de um
  nó).
- Compare-and-set de pointer via `@Version`.
- Representação de UUID configurada explicitamente.

Não usar mock de `MongoTemplate` para validar query, índice ou comportamento transacional. A imagem do container é a
mesma versão de servidor do ambiente produtivo.

### 6.4 Adapter Redis

Usar Testcontainers Redis para validar:

- Codec do JSON armazenado (mesmo `JsonMapper` do runtime).
- TTL de árvore e lastgood.
- Chaves normalizadas.
- Hash de capabilities ordenadas.
- `SET NX` e lease do singleflight.
- Liberação segura do lease.
- Invalidação seletiva por surface/platform/channel.
- Comportamento quando Redis está indisponível.
- Rate limit atômico, se implementado neste adapter.

Container: `GenericContainer` com `@ServiceConnection(name = "redis")`, sem a dependência de terceiros
`com.redis:testcontainers-redis`. Não validar TTL ou script Lua com mock de `RedisTemplate`.

### 6.5 API: `@WebMvcTest` + MockMvc

```kotlin
@WebMvcTest(controllers = [ScreenController::class])
class ScreenControllerTest(@Autowired private val mockMvc: MockMvc) {

    @MockkBean
    private lateinit var composeScreenUseCase: ComposeScreenUseCase

    @Test
    fun `exige headers de negociacao`() {
        mockMvc.get("/v1/surfaces/home")
            .andExpect { status { isBadRequest() } }
    }
}
```

`@MockkBean` vem de `springmockk`, com versão verificada como compatível com Spring Boot 4.1 antes de entrar no
catálogo. Não misturar `@MockitoBean` e `@MockkBean` no projeto.

Testar no slice MVC:

- Headers obrigatórios e opcionais.
- `API-Version` separado de `UI-Schema-Version`.
- `Client-Platform`, `Client-Version`, `Client-Build`, `OS-Version` e capabilities.
- Resposta 200.
- Resposta 304 quando `If-None-Match` casar.
- `503` + `Retry-After` com corpo estável (ADR-007).
- JSON do envelope.
- Erros de validação.
- `ApiExceptionHandler`.
- Rotas administrativas e autorização, com configuração de segurança apropriada.

`REST Assured` pode ser adicionado depois para testes de contrato HTTP contra servidor real. Não é necessário para
começar se MockMvc cobre o comportamento do controller.

### 6.6 Serialização do contrato

Os testes de serialização validam o JSON de fio com o **mesmo `JsonMapper` que o Boot autoconfigura** (ADR-005). Por
isso eles vivem em `sdui-app` (slice de JSON do Boot, ou `@WebMvcTest` verificando o corpo), não em `sdui-contract`,
que não tem Boot. Um mapper construído à mão no teste pode passar enquanto a resposta HTTP sai diferente.

Cobertura mínima: nomes de campo, ausência de campos nulos conforme contrato, datas ISO-8601, ausência de campos
proibidos (aparência, geometria, variação de renderização) e ausência de `required` (ADR-009) no payload.

### 6.7 WireMock: somente se houver client HTTP

WireMock entra apenas quando existir um adapter HTTP real de hidratação, com versão explícita no catálogo (não é
gerenciado pelo BOM). Ele deve testar:

- Resposta 2xx e mapeamento.
- 4xx/5xx.
- Timeout.
- Conexão recusada.
- Payload inválido.
- Retry somente em condições permitidas.
- Circuit breaker, se existir.
- Propagação de headers técnicos permitidos.
- Limite de resposta e comportamento de fallback.

Se o serviço não tiver client HTTP no MVP, não adicionar WireMock ao projeto. Mongo e Redis não precisam dele.

### 6.8 Integração/E2E leve

```kotlin
@SpringBootTest(
    classes = [SduiApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class ComposeHomeIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmField
        val mongo = MongoDBContainer("mongo:<versao-de-producao>")

        @Container
        @ServiceConnection(name = "redis")
        @JvmField
        val redis = GenericContainer<Nothing>("redis:<versao-de-producao>").apply { withExposedPorts(6379) }
    }

    @Test
    fun `compoe home com mongo e redis reais`() {
        // Request HTTP real contra porta aleatória.
        // Assert de status, envelope, ETag, cache e fallback.
    }
}
```

`@JvmField` no `companion object` é obrigatório: a extensão do Testcontainers e o `@ServiceConnection` procuram campos
estáticos. No Testcontainers 2.x o pacote de `MongoDBContainer` mudou junto com os artefatos (`testcontainers-mongodb`);
usar os imports da versão do BOM.

Cobertura mínima:

- Primeiro request: miss + compose + gravação de cache.
- Segundo request: hit de árvore.
- `If-None-Match`: 304.
- Redis indisponível: lastgood, conforme política.
- Redis vazio e sem lastgood: `503` + `Retry-After`, nunca `500` (ADR-007).
- Spec incompatível: fallback 200, não 404.
- Section desconhecida: omitted.
- Publish: revisão, pointer e auditoria.
- Rollback: pointer anterior sem alteração da revisão publicada.

Para testes sem servidor real, `@SpringBootTest` + `@AutoConfigureMockMvc` é suficiente.

---

## 7. ArchUnit e regras arquiteturais

As regras protegem a direção das dependências e os limites do projeto, não impõem nomenclatura sem valor. Com
`orchestrator`, `adapters` e `api` no mesmo módulo, **o ArchUnit é a única proteção entre essas três camadas** — por
isso as regras abaixo são obrigatórias, não recomendações.

### 7.1 Dependência e forma de uso

No módulo `sdui-integration-test`, que enxerga todas as classes de produção:

```kotlin
// sdui-integration-test/build.gradle.kts
dependencies {
    testImplementation(libs.archunit)          // com.tngtech.archunit:archunit:1.5.0
}
```

Usar o artefato `archunit` dentro de testes JUnit Jupiter comuns, com `ClassFileImporter`. Não usar o engine
`archunit-junit5`: ele depende da compatibilidade de um TestEngine extra com a versão de JUnit do BOM, e
`@ArchTest static final` não existe em Kotlin (exigiria `@JvmField` em `companion object` para cada regra).

### 7.2 Regras mínimas

```kotlin
class ArchitectureTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("br.com.empresa.sdui")

    @Test
    fun `core nao depende de frameworks nem de jackson`() {
        noClasses().that().resideInAPackage("..sdui.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "org.mongodb..", "com.mongodb..", "io.lettuce..",
                "jakarta.servlet..", "tools.jackson..", "com.fasterxml.jackson..",
                "..sdui.contract..", "..sdui.orchestrator..", "..sdui.adapters..", "..sdui.api..",
            )
            .check(classes)
    }

    @Test
    fun `orchestrator nao depende de spring, contrato nem bordas`() {
        noClasses().that().resideInAPackage("..sdui.orchestrator..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta.servlet..", "tools.jackson..", "com.fasterxml.jackson..",
                "..sdui.contract..", "..sdui.adapters..", "..sdui.api..",
            )
            .check(classes)
    }

    @Test
    fun `api nao acessa adapters`() {
        noClasses().that().resideInAPackage("..sdui.api..")
            .should().dependOnClassesThat().resideInAPackage("..sdui.adapters..")
            .check(classes)
    }

    @Test
    fun `adapters nao dependem de api nem do contrato`() {
        noClasses().that().resideInAPackage("..sdui.adapters..")
            .should().dependOnClassesThat().resideInAnyPackage("..sdui.api..", "..sdui.contract..")
            .check(classes)
    }

    @Test
    fun `somente bootstrap inicia spring boot`() {
        classes().that().areAnnotatedWith(SpringBootApplication::class.java)
            .should().resideInAPackage("..sdui.bootstrap..")
            .check(classes)
    }

    @Test
    fun `controllers somente em api`() {
        classes().that().areAnnotatedWith(RestController::class.java)
            .should().resideInAPackage("..sdui.api..")
            .check(classes)
    }
}
```

Enquanto os pacotes estiverem vazios (bootstrap/H00), regras que não encontram classes falham por padrão. Usar
`.allowEmptyShould(true)` somente nessas regras, com comentário apontando a história que remove a exceção, e registrar
a pendência no `AGENTS.md`.

Regras do tipo "só pode depender de X" (listas de permissão) precisam liberar `kotlin..`, `org.jetbrains.annotations..`
e `java..`. As regras acima são listas de proibição e não têm esse problema. Classes sintéticas geradas pelo Kotlin
(`*Kt`, `$Companion`, `$WhenMappings`) residem no mesmo pacote do fonte e são cobertas normalmente.

### 7.3 Regras adicionais

- Classes `@Document` só em `..adapters.mongo.document..`.
- `MongoTemplate` e `RedisTemplate` só em `..adapters..`.
- `@Transactional` não aparece em controllers, filters ou adapters, **com uma única exceção nominal**:
  `..adapters.mongo.tx.MongoTransactionalUnitOfWork`, que implementa a porta `TransactionalUnitOfWork` (ADR-003). A
  exceção é por nome de classe, não por pacote. Se o ADR-013 for aceito, a exceção desaparece e a regra vira "nenhum
  `@Transactional` no projeto".
- Nenhuma classe de `..sdui.core..` ou `..sdui.orchestrator..` depende de `org.springframework.transaction..`.
- Nenhuma classe fora de `..adapters.mongo.tx..` implementa `TransactionalUnitOfWork`.
- Nenhuma classe de `..core..` ou `..orchestrator..` referencia `ComposeTraceContext` (ADR-002).
- `SectionHydrator` reside em `..orchestrator.hydration..`; implementações residem em `..adapters..`.
- DTOs públicos residem em `contract`; documentos de banco em `..adapters.mongo..`.
- Classes terminadas em `Controller` só em `api`.
- Nenhuma dependência entre `..adapters.mongo..`, `..adapters.redis..` e `..adapters.http..`.
- Nenhuma classe de produção declara `suspend fun` nem depende de `kotlinx.coroutines..` (ADR-012).

### 7.4 ArchUnit e fronteiras Gradle

ArchUnit verifica dependências no bytecode, mas não substitui a separação de módulos. Os dois níveis são usados:

- **Gradle** impede que uma dependência exista no classpath: `sdui-core` e `sdui-contract` não enxergam Spring, e a task
  `verifyPureClasspath` falha o build se isso mudar. `verifyForbiddenDependencies` impede gRPC, Protobuf, GraphQL,
  MapStruct e Kafka em qualquer módulo.
- **ArchUnit** impede violações entre pacotes dentro do grafo permitido, principalmente dentro de `sdui-app`.

Se uma fronteira puder ser garantida pelo Gradle, ela não deve depender só do ArchUnit.

---

## 8. Build Gradle

### 8.1 `settings.gradle.kts`

```kotlin
pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "<versao-estavel-verificada>"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ms-sdui-composer"

include(
    "sdui-contract",
    "sdui-core",
    "sdui-app",
    "sdui-bootstrap",
    "sdui-integration-test",
)
```

O `build.gradle.kts` da raiz fica vazio (ou só com `apply false`). Não usar `allprojects {}` nem `subprojects {}`: o
Gradle 9.6 deprecou lookups na hierarquia de projetos, e toda configuração compartilhada vive nos convention plugins.

### 8.2 `gradle/libs.versions.toml`

```toml
[versions]
kotlin = "2.4.20"
spring-boot = "4.1.1"
archunit = "1.5.0"
# mockk = "<verificar>"                                  # entra quando uma história exigir
# springmockk = "<verificar compatibilidade com Boot 4.1>"
# wiremock = "<verificar>"                               # somente com client HTTP real

[libraries]
# Plugins usados por build-logic
kotlin-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
kotlin-allopen = { module = "org.jetbrains.kotlin:kotlin-allopen", version.ref = "kotlin" }
spring-boot-gradle-plugin = { module = "org.springframework.boot:spring-boot-gradle-plugin", version.ref = "spring-boot" }

# BOM
spring-boot-bom = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "spring-boot" }

# Fora do BOM: versão explícita
archunit = { module = "com.tngtech.archunit:archunit", version.ref = "archunit" }

# Gerenciadas pelo BOM: SEM versão
kotlin-reflect = { module = "org.jetbrains.kotlin:kotlin-reflect" }
jackson-annotations = { module = "com.fasterxml.jackson.core:jackson-annotations" }
jackson-databind = { module = "tools.jackson.core:jackson-databind" }
jackson-module-kotlin = { module = "tools.jackson.module:jackson-module-kotlin" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core" }
spring-boot-starter-webmvc = { module = "org.springframework.boot:spring-boot-starter-webmvc" }
spring-boot-starter-webmvc-test = { module = "org.springframework.boot:spring-boot-starter-webmvc-test" }
spring-boot-starter-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
spring-boot-starter-data-mongodb = { module = "org.springframework.boot:spring-boot-starter-data-mongodb" }
spring-boot-starter-data-redis = { module = "org.springframework.boot:spring-boot-starter-data-redis" }
spring-boot-starter-restclient = { module = "org.springframework.boot:spring-boot-starter-restclient" }
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }
spring-boot-testcontainers = { module = "org.springframework.boot:spring-boot-testcontainers" }
testcontainers-junit-jupiter = { module = "org.testcontainers:testcontainers-junit-jupiter" }
testcontainers-mongodb = { module = "org.testcontainers:testcontainers-mongodb" }
micrometer-registry-prometheus = { module = "io.micrometer:micrometer-registry-prometheus" }
```

Os nomes de starters seguem a modularização do Spring Boot 4 e devem ser conferidos no BOM 4.1.1 antes do primeiro
uso. Os artefatos de Testcontainers usam os nomes da linha 2.x (`testcontainers-*`).

### 8.3 `build-logic/`

`build-logic/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
```

`build-logic/build.gradle.kts`:

```kotlin
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.spring.boot.gradle.plugin)
}
```

Dentro de precompiled script plugins o acessor tipado `libs` não existe; usar
`extensions.getByType<VersionCatalogsExtension>().named("libs")`.

### 8.4 Convention plugins

| Plugin                | Aplica                                                                                          | Usado por                           |
|-----------------------|-------------------------------------------------------------------------------------------------|-------------------------------------|
| `sdui.kotlin-base`    | `kotlin("jvm")`, `java-library`, toolchain 25, flags, BOM, JUnit, `verifyForbiddenDependencies` | todos, indiretamente                |
| `sdui.kotlin-library` | `sdui.kotlin-base` + `verifyPureClasspath`                                                      | `sdui-core`, `sdui-contract`        |
| `sdui.spring-library` | `sdui.kotlin-base` + `kotlin("plugin.spring")` + `kotlin-reflect`                               | `sdui-app`, `sdui-integration-test` |
| `sdui.spring-app`     | `sdui.spring-library` + `org.springframework.boot`                                              | `sdui-bootstrap`                    |

`sdui.kotlin-base.gradle.kts` (esboço):

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform(lib("spring-boot-bom")))
    testImplementation(platform(lib("spring-boot-bom")))
    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("assertj-core"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val verifyForbiddenDependencies by tasks.registering(VerifyDependencies::class) {
    forbiddenGroupPrefixes.set(
        listOf(
            "io.grpc", "com.google.protobuf", "org.springframework.grpc",
            "com.graphql-java", "org.springframework.graphql",
            "org.mapstruct", "org.apache.kafka",
        ),
    )
    rootComponent.set(configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent })
}

tasks.named("check") { dependsOn(verifyForbiddenDependencies) }
```

`-Xannotation-default-target=param-property` faz annotations em parâmetros de construtor (`@JsonProperty`, `@Id`,
`@field:...`) caírem no parâmetro e na propriedade, como o Spring e o Jackson esperam.

`sdui.kotlin-library.gradle.kts` acrescenta:

```kotlin
plugins {
    id("sdui.kotlin-base")
}

val verifyPureClasspath by tasks.registering(VerifyDependencies::class) {
    forbiddenGroupPrefixes.set(listOf("org.springframework", "org.mongodb", "io.lettuce", "jakarta.servlet"))
    rootComponent.set(configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent })
}

tasks.named("check") { dependsOn(verifyPureClasspath) }
```

### 8.5 Task `VerifyDependencies`

Vive em `build-logic/src/main/kotlin/VerifyDependencies.kt`. Recebe o resultado da resolução como input (compatível com
configuration cache) e **ignora componentes de plataforma** — sem isso, o próprio BOM
`org.springframework.boot:spring-boot-dependencies` seria acusado como dependência Spring em `sdui-core`.

```kotlin
abstract class VerifyDependencies : DefaultTask() {

    @get:Input
    abstract val forbiddenGroupPrefixes: ListProperty<String>

    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @TaskAction
    fun verify() {
        val forbidden = forbiddenGroupPrefixes.get()
        val seen = mutableSetOf<ComponentIdentifier>()
        val violations = sortedSetOf<String>()

        fun ResolvedComponentResult.isPlatform(): Boolean = variants.any { variant ->
            val key = variant.attributes.keySet().firstOrNull { it.name == "org.gradle.category" }
            val category = key?.let { variant.attributes.getAttribute(it)?.toString() }
            category == "platform" || category == "enforced-platform"
        }

        fun visit(component: ResolvedComponentResult) {
            if (!seen.add(component.id)) return
            val id = component.id
            if (id is ModuleComponentIdentifier && !component.isPlatform() &&
                forbidden.any { id.group.startsWith(it) }
            ) {
                violations += id.displayName
            }
            component.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { visit(it.selected) }
        }

        visit(rootComponent.get())
        if (violations.isNotEmpty()) {
            throw GradleException("Dependências proibidas no runtimeClasspath: $violations")
        }
    }
}
```

### 8.6 `gradle.properties` e wrapper

```properties
org.gradle.configuration-cache=true
org.gradle.caching=true
org.gradle.parallel=true
kotlin.code.style=official
```

O wrapper é gerado por um Gradle instalado localmente, com checksum (ver `iniciar-prompt.md`), e o
`gradle-wrapper.properties` contém `distributionSha256Sum`. O `gradle-wrapper.jar` nunca é escrito à mão.

---

## 9. `build.gradle.kts` dos módulos

### 9.1 `sdui-core`

```kotlin
plugins {
    id("sdui.kotlin-library")
}
```

Nenhuma dependência de produção além da stdlib Kotlin.

### 9.2 `sdui-contract`

```kotlin
plugins {
    id("sdui.kotlin-library")
}

dependencies {
    api(libs.jackson.annotations)
    api(libs.jackson.databind)      // somente enquanto o envelope usar JsonNode para `data`
}
```

Jackson 3 (ADR-005): `tools.jackson.core:jackson-databind` + `com.fasterxml.jackson.core:jackson-annotations`, que
mantém o groupId antigo. Declarar `com.fasterxml.jackson.core:jackson-databind` colocaria dois databinds no classpath.

### 9.3 `sdui-app`

```kotlin
plugins {
    id("sdui.spring-library")
}

dependencies {
    api(project(":sdui-core"))
    implementation(project(":sdui-contract"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.module.kotlin)

    // Entram na história que implementa o adapter correspondente, não no bootstrap do repositório (H00):
    // implementation(libs.spring.boot.starter.data.mongodb)
    // implementation(libs.spring.boot.starter.data.redis)
    // implementation(libs.spring.boot.starter.restclient)   // somente com hidratação HTTP real

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.test)
    // Com os adapters:
    // testImplementation(libs.spring.boot.testcontainers)
    // testImplementation(libs.testcontainers.junit.jupiter)
    // testImplementation(libs.testcontainers.mongodb)
    // Quando uma história exigir:
    // testImplementation(libs.mockk)
    // testImplementation(libs.springmockk)
    // testImplementation(libs.wiremock)                     // somente com client HTTP real
}
```

`api(project(":sdui-core"))` é obrigatório: portas e casos de uso expõem tipos do core nas assinaturas, e o bootstrap
precisa enxergá-los para fazer o wiring. `sdui-contract` é `implementation` porque só a camada `api` o usa.

`jackson-module-kotlin` é o que permite ao `JsonMapper` do Boot (de)serializar `data class` com construtor primário e
nulabilidade Kotlin. Sem ele, a desserialização de fixtures e requests administrativos falha.

### 9.4 `sdui-bootstrap`

```kotlin
plugins {
    id("sdui.spring-app")
}

dependencies {
    implementation(project(":sdui-app"))
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(libs.spring.boot.starter.test)
}
```

O plugin do Boot detecta a classe principal. Se for preciso declará-la, lembrar que o `main` top-level de Kotlin é
compilado em `SduiApplicationKt`: `springBoot { mainClass.set("br.com.empresa.sdui.bootstrap.SduiApplicationKt") }`.

### 9.5 `sdui-integration-test`

```kotlin
plugins {
    id("sdui.spring-library")
}

dependencies {
    testImplementation(project(":sdui-bootstrap"))
    testImplementation(project(":sdui-app"))
    testImplementation(project(":sdui-core"))
    testImplementation(project(":sdui-contract"))

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit)
    // Com os adapters:
    // testImplementation(libs.spring.boot.testcontainers)
    // testImplementation(libs.testcontainers.junit.jupiter)
    // testImplementation(libs.testcontainers.mongodb)
}
```

Nada em `src/main`. Os testes de integração rodam na task `test` deste módulo; não há separação `*IT` como no Failsafe.

---

## 10. YAML e configuração

### `sdui-bootstrap/src/main/resources/application.yaml`

```yaml
spring:
  application:
    name: ms-sdui-composer

  threads:
    virtual:
      enabled: true

  mvc:
    apiversion:
      use:
        header: API-Version
      default: "1"
      required: true
      supported: "1"

  # Boot 4: propriedades de conexão do MongoDB saíram de `spring.data.mongodb.*` para `spring.mongodb.*`.
  # Usar o prefixo antigo faz a aplicação cair silenciosamente no default `mongodb://localhost/test`.
  mongodb:
    uri: ${MONGODB_URI}
    representation:
      uuid: standard

  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT:6379}
      timeout: ${REDIS_TIMEOUT:50ms}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics

  endpoint:
    health:
      probes:
        enabled: true

  metrics:
    tags:
      application: ${spring.application.name}

sdui:
  compose:
    deadline: ${SDUI_COMPOSE_DEADLINE:400ms}
    tree-ttl: ${SDUI_TREE_TTL:60s}
    last-good-ttl: ${SDUI_LAST_GOOD_TTL:15m}
    singleflight-lease: ${SDUI_SINGLEFLIGHT_LEASE:3s}
    singleflight-wait-budget: ${SDUI_SINGLEFLIGHT_WAIT_BUDGET:250ms}
    max-sections: ${SDUI_MAX_SECTIONS:20}
    max-payload-bytes: ${SDUI_MAX_PAYLOAD_BYTES:131072}

  hydration:
    enabled: ${SDUI_HYDRATION_ENABLED:false}
    max-concurrency: ${SDUI_HYDRATION_MAX_CONCURRENCY:8}
    section-timeout: ${SDUI_SECTION_TIMEOUT:150ms}

  rate-limit:
    enabled: ${SDUI_RATE_LIMIT_ENABLED:true}
    capacity: ${SDUI_RATE_LIMIT_CAPACITY:60}
    refill-tokens: ${SDUI_RATE_LIMIT_REFILL_TOKENS:60}
    refill-period: ${SDUI_RATE_LIMIT_REFILL_PERIOD:1m}
```

As chaves `spring.mongodb.*` e `spring.data.redis.*` só passam a ser lidas quando os starters de dados entrarem em
`sdui-app`. No bootstrap do repositório (H00), o `application.yaml` pode conter apenas `spring.application.name`,
`spring.threads.virtual.enabled` e o bloco `sdui` que já tenha consumidor.

As properties ficam somente em `.yaml`. O binding usa `@ConfigurationProperties` tipado em `data class`, nunca `@Value`
espalhado:

```kotlin
@ConfigurationProperties(prefix = "sdui.hydration")
data class HydrationProperties(
    val enabled: Boolean,
    val maxConcurrency: Int,
    val sectionTimeout: Duration,
)
```

Sem valores default no Kotlin: a fonte única de default é o YAML. Propriedade ausente e não-nula falha na subida, que é
o comportamento desejado. As classes são registradas por `@ConfigurationPropertiesScan` na `SduiApplication`.

---

## 11. Regras para hidratação paralela

Hidratação paralela faz sentido somente com escopo e orçamento claros.

### Regras obrigatórias

- O número máximo de hydrators concorrentes é limitado **por instância e por dependência**, não por request. Um
  `Semaphore` criado dentro de cada compose não protege a API da squad quando cem requests chegam juntos.
- Cada section tem timeout próprio, menor que o deadline total do compose.
- O compose espera apenas o escopo que iniciou; nenhuma tarefa fica órfã.
- Falhas viram resultado terminal: section omitida, fallback ou erro de compose conforme contrato.
- Não usar `CompletableFuture` sem `handle`/`exceptionally`.
- Não usar `Executors.newFixedThreadPool` para I/O de hydrators.
- Virtual Threads não eliminam a necessidade de limitar concorrência na API externa.
- Não segurar lock durante I/O. Desde o Java 24 (JEP 491) `synchronized` não prende mais a virtual thread ao carrier,
  mas lock segurado durante chamada de rede continua gerando contenção.
- Sem coroutines e sem `suspend` (ADR-012).
- Sem `StructuredTaskScope` (preview no Java 25).

### Esboço em Kotlin

```kotlin
class HydrationCoordinator(
    private val registry: HydratorRegistry,
    maxConcurrency: Int,
    private val sectionTimeout: Duration,
    private val clock: Clock,
) {
    // Compartilhado entre requests: protege a dependência, não só o request.
    private val permits = Semaphore(maxConcurrency)

    fun hydrateAll(context: HydrationContext, sections: List<Section>, deadline: Instant): List<HydrationOutcome> {
        val sectionDeadline = minOf(clock.instant().plus(sectionTimeout), deadline)

        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = sections.map { section ->
                section to executor.submit<HydrationResult> {
                    permits.acquire()
                    try {
                        registry.hydratorFor(section).hydrate(context, section)
                    } finally {
                        permits.release()
                    }
                }
            }
            futures.map { (section, future) -> awaitOrCancel(section, future, sectionDeadline) }
        }
    }

    private fun awaitOrCancel(section: Section, future: Future<HydrationResult>, until: Instant): HydrationOutcome {
        val remainingMillis = Duration.between(clock.instant(), until).toMillis().coerceAtLeast(0)
        return try {
            HydrationOutcome.Hydrated(section, future.get(remainingMillis, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            future.cancel(true)
            HydrationOutcome.Omitted(section, OmissionReason.TIMEOUT)
        } catch (e: ExecutionException) {
            HydrationOutcome.Omitted(section, OmissionReason.ERROR)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            HydrationOutcome.Omitted(section, OmissionReason.INTERRUPTED)
        }
    }
}
```

O coordenador recebe `maxConcurrency` e `sectionTimeout` como valores, não `HydrationProperties`: properties são do
bootstrap, e o orchestrator não pode depender dele. O wiring em `ApplicationWiringConfiguration` faz a ponte.

Três detalhes que o esboço torna explícitos:

1. **`Future.get(timeout)` não cancela a tarefa.** Sem `future.cancel(true)` no `catch`, a hidratação continua rodando
   depois que o compose já respondeu — exatamente a tarefa órfã que esta seção proíbe.
2. **`use {}` espera todas as tarefas terminarem** (`ExecutorService.close()` aguarda término). Cancelar interrompe a
   virtual thread, mas I/O que não responde a interrupção segura o request além do deadline. Por isso todo client usado
   por hydrator tem timeout próprio de conexão e leitura **menor ou igual** ao `section-timeout`.
3. **`acquire()` fica fora do `try`.** Se a tarefa for cancelada enquanto espera a permissão, não há `release()` de uma
   permissão que nunca foi obtida.

Se o limite precisar ser por dependência (squads diferentes com SLAs diferentes), o `Semaphore` passa para dentro do
`HydratorRegistry`, um por hydrator. O valor de `sdui.hydration.max-concurrency` deve ser validado contra o SLA e o rate
limit das APIs das squads.

---

## 12. Controller e contrato HTTP

### Endpoint de tela

```text
GET /v1/surfaces/{surface}
```

O endpoint é `GET /v1/surfaces/home` no MVP. Não existe endpoint de fragment (`GET /v1/fragments/{fragmentId}`) nem
rota pública para blocos parciais; ver ADR-004 e §5.

### Headers

O contrato usa headers sem prefixo `X-`:

```text
API-Version: 1
UI-Schema-Version: 3
Client-Platform: ios
Client-Version: 8.14.2
Client-Build: 81420
Accept-Language: pt-BR
OS-Version: 18.1
Component-Capabilities: top_bar@1,account_card@1
```

Não substituir esses nomes por `X-Platform`, `X-App-Version` ou `X-Capabilities`, pois isso contradiz a decisão do
projeto.

### Respostas

- `200`: árvore completa ou fallback `lastgood`.
- `304`: `If-None-Match` coincide com ETag.
- `400`: header ausente, inválido ou incompatível com o contrato HTTP.
- `401/403`: autenticação/autorização conforme segurança corporativa.
- `429`: rate limit.
- `500`: somente erro não tratado fora da escada de fallback; timeout de section não deve produzir 500 por padrão.
- `503` + `Retry-After`: **último degrau da escada de fallback**. Ocorre quando não há árvore compatível e também não
  existe `lastgood` para `surface + platform + channel` (boot frio, Redis vazio, primeira subida). Corpo estável e
  previsível, sem stacktrace. Nunca usar `500` nesse caso: o app precisa do sinal de retry.

A Home não retorna 404 quando não há spec compatível; usa última árvore boa ou uma resposta de fallback definida pelo
contrato (`plano-servico-sdui.md`).

Escada completa, do melhor para o pior caso:

```text
200 árvore composta (cache hit ou miss composto com sucesso)
200 árvore composta com sections omitidas  -> envelope.omitted preenchido
200 lastgood                               -> fallback=true + fallbackReason
503 + Retry-After                          -> sem árvore e sem lastgood
```

O app mobile cobre o degrau seguinte com o skeleton local mínimo do binário. Isso é contrato com o time mobile, não
código deste MS.

---

## 13. Métricas e observabilidade

Métricas mínimas:

```text
sdui.compose.hit
sdui.compose.miss
sdui.compose.fallback
sdui.compose.singleflight.wait
sdui.compose.duration
sdui.section.duration
sdui.section.omitted
sdui.payload.bytes
sdui.serialize.duration
sdui.hydration.timeout
sdui.hydration.error
sdui.hydration.permits.wait
```

Tags permitidas e de baixa cardinalidade:

```text
surface
platform
schemaVersion
appMajorMinor
channel
outcome
cache
componentType
fallbackReason
```

Evitar `userId`, request ID, capability bruta e `specRevisionId` sem controle de cardinalidade.

Traces devem mostrar:

```text
compose
├── cache.tree
├── resolve.pointer
├── select.spec
├── hydrate.sections
│   ├── section.top_bar
│   └── section.account_card
├── assemble.envelope
└── serialize.response
```

Métricas, tags e spans de fragment não existem no MVP (ADR-004).

---

## 14. Ajustes consolidados à proposta original

### O que foi aprovado

- Separação lógica em `contract`, `core`, `orchestrator`, `adapters`, `api` e `bootstrap`, materializada em **quatro
  módulos** (ADR-001).
- SPI `SectionHydrator` para hidratação por squad, bloqueante e bounded.
- JUnit Jupiter + AssertJ + MockK/fakes para unitários.
- Testcontainers para Mongo/Redis, somente em adapter e integração.
- `@WebMvcTest` + MockMvc (DSL Kotlin) para controller.
- ArchUnit para regras de dependência entre pacotes, obrigatório dentro de `sdui-app`.
- `@SpringBootTest` com Testcontainers para integração/E2E leve.
- WireMock apenas quando existir client HTTP de hidratação.

### O que foi ajustado

1. **`sdui-core` não é simples validação de contexto.** Contém as invariantes SDUI completas: compatibilidade,
   omissão, targeting e slot portante.
2. **O `orchestrator` é dono dos casos de uso**, não apenas um pipeline técnico, e não conhece Spring.
3. **`adapters` não depende de DTOs do `contract`.** Mapeamento por funções de extensão a partir de domínio/application.
4. **`@JsonTypeInfo` não é obrigatório.** Só com polimorfismo de desserialização realmente necessário.
5. **Fragment fora do MVP**, em todas as camadas (ADR-004).
6. **Hydrators são bounded por instância/dependência**, com cancelamento explícito em timeout (§11).
7. **Sem RestClient/WireMock sem client HTTP real.**
8. **ArchUnit protege regras reais**, e dentro de `sdui-app` é a única fronteira entre camadas.
9. **Testcontainers não pertence a unitários.**
10. **Headers sem `X-`**, conforme o projeto.
11. **Starter MVC de teste do Boot 4:** `spring-boot-starter-webmvc-test`.
12. **`API-Version` separado de `UI-Schema-Version`** (`plano-servico-sdui.md`).
13. **Jackson 3 com módulo Kotlin** (ADR-005).
14. **Resilience4j fora do MVP** (ADR-006).
15. **`503` + `Retry-After` como último degrau da escada** (ADR-007).
16. **Transação de publish via porta `TransactionalUnitOfWork`** (ADR-003; alternativa proposta no ADR-013).
17. **Omissão pura com slot portante declarado** (ADR-009).
18. **`SectionComponentType` não entra no contrato** (ADR-010).
19. **Conjunto de actions fechado em quatro** (ADR-011).
20. **Kotlin em toda a produção e testes; build Gradle com convention plugins**, sem MapStruct/`kapt`.
21. **Sem coroutines no MVP** (ADR-012).
22. **Propriedades do MongoDB no prefixo `spring.mongodb.*`** e representação de UUID explícita (§10).
23. **Versões gerenciadas pelo BOM nunca são fixadas**; bibliotecas fora do BOM ficam no catálogo com versão verificada.

---

## 15. Ordem de implementação

1. Criar `settings.gradle.kts`, catálogo, `build-logic/` com os convention plugins e `VerifyDependencies`, e o Gradle
   Wrapper com checksum.
2. Criar `sdui-contract`, `sdui-core`, `sdui-app`, `sdui-bootstrap` e `sdui-integration-test`.
3. Implementar o core com testes unitários, sem qualquer Spring.
4. Implementar casos de uso e portas do orchestrator (pacote em `sdui-app`), com fakes em memória.
5. Implementar o contrato v1 e os testes de serialização com o `JsonMapper` do Boot.
6. Implementar resolução de Screen sem hidratação remota (sem fragments — ADR-004).
7. Implementar cache Redis para Screen/lastgood/singleflight (starter de Redis entra aqui).
8. Implementar Mongo para spec, pointer, skeleton e auditoria (starter de MongoDB entra aqui).
9. Implementar API MVC e `@WebMvcTest`.
10. Endurecer ArchUnit: remover todo `allowEmptyShould(true)` restante e fazer o build falhar em violação.
11. Implementar hydrators somente para squads/clientes efetivamente definidos.
12. Adicionar client HTTP e WireMock apenas nesse momento.
13. Criar integração completa com `@SpringBootTest` + Mongo/Redis Testcontainers.
14. Fechar segurança, rate limit, maker-checker e idempotência.
15. Executar carga e validar P99, contenção de singleflight, tamanho de payload e comportamento de fallback.

---

## 16. Decisões registradas (ADR)

Formato curto: contexto, decisão, consequência. Um ADR só muda por outro ADR que o supersede. O projeto tem **um único
engenheiro de backend**; isso não dispensa o registro, ao contrário: o ADR é o que impede a decisão de ser reaberta por
esquecimento daqui a três meses.

Legenda de status:

- `ACEITO`: fechado, vale para o código.
- `PROPOSTO`: recomendação registrada; **não vale para o código** até ser promovida a `ACEITO`.
- `PENDENTE`: decisão aberta que bloqueia a parte afetada.

---

### ADR-001 — Modularização: quatro módulos de produção

**Status:** `ACEITO` (18/09/2026). Supersede a versão anterior deste ADR, que recomendava três módulos Maven.

**Contexto.** O `plano-servico-sdui.md` §13.2 se intitula "Pacotes (simples, sem hexágono)" e propõe um layout achatado
de pacotes num artefato único. A premissa 12 do §0 põe "Hexágono neste MS" fora de escopo. A versão anterior desta
pré-arquitetura entregava sete módulos com `port/in/`, `port/out/` e módulo `adapters`.

**Análise.** O benefício real de sete módulos é fronteira compilada e ownership separado por squad. Com um engenheiro, o
ownership não existe. A migração para Gradle reduz o custo de módulos (convention plugins, compile avoidance, build
paralelo), mas não cria ownership. Das fronteiras compiladas, só uma protege algo que o ArchUnit protegeria pior: a
pureza do core, porque é onde Spring e infraestrutura vazam mais facilmente e porque um classpath sem Spring impede o
erro em vez de detectá-lo depois.

**Decisão.** Quatro módulos de produção e um de teste:

```text
sdui-contract          -> DTOs REST/JSON públicos (fronteira de processo)
sdui-core              -> domínio puro, fronteira compilada + verifyPureClasspath
sdui-app               -> orchestrator + adapters + api, separados por pacote e protegidos por ArchUnit
sdui-bootstrap         -> main, wiring, YAML, observabilidade (único executável)
sdui-integration-test  -> integração real e ArchUnit (somente testes)
```

**Consequência.**

- Os §2, §3, §7, §8, §9 e §15 deste documento já refletem a decisão.
- O §13.2 do plano deve ganhar nota apontando para este ADR: o layout de pacotes do plano vale dentro de `sdui-app`, e
  `core`/`contract` saem para módulos próprios.
- O `iniciar-prompt.md` usa os mesmos cinco projetos.
- Gatilhos para dividir `sdui-app`: ownership distinto, deploy distinto, ou regra ArchUnit violada repetidamente para o
  código compilar (§2).

---

### ADR-002 — Contexto de trace: `ComposeTraceContext` com `ThreadLocal`

**Status:** `ACEITO`

**Contexto.** O plano §11 fecha `ThreadLocal` para `surface`, `platform` e `specRevisionId`, com `remove()` garantido no
`finally` da borda da request. Em Java 25, `ScopedValue` já é permanente (JEP 506), mas não é requisito deste MS:
contexto funcional continua explícito em `ComposeRequest`, e o contexto de observabilidade permanece encapsulado na
borda HTTP. Esta pré-arquitetura não mencionava `ThreadLocal` em lugar nenhum.

**Decisão.** Criar `br.com.empresa.sdui.api.context.ComposeTraceContext`:

- Escopo **exclusivo de observabilidade**: MDC de log, tag de métrica e atributo de span. Nunca dado funcional.
- Dado funcional continua viajando por parâmetro explícito em `ComposeRequest`. Se uma classe precisa de `platform` para
  decidir algo, ela recebe `platform`; não lê do `ThreadLocal`.
- Aberto e fechado no `CorrelationIdInterceptor`, com `remove()` em `finally`. A virtual thread é descartada por
  request, mas o `finally` protege contra reuso do carrier e contra troca futura para pool.
- Vive no pacote `api` de `sdui-app` porque é infraestrutura HTTP. `core` e `orchestrator` continuam sem conhecê-lo.

**Consequência.** Uma regra ArchUnit adicional (§7.3): nenhuma classe de `..core..` ou `..orchestrator..` pode
referenciar
`ComposeTraceContext`. Se alguém precisar dele lá, o dado deveria estar na assinatura.

**Nota sobre a referência.** A validação inicial citava "§11/§13.1" do plano. O §13.1 é apenas a lista de runtime e só
referencia o §11 para o veto ao `ScopedValue`. A decisão de `ThreadLocal` vive **só no §11**.

---

---

### ADR-003 — Transação de publish: porta `TransactionalUnitOfWork`

**Status:** `SUPERSEDIDO pelo ADR-013` (A porta `TransactionalUnitOfWork` permanece; o mecanismo de `@Transactional` foi substituído por `TransactionTemplate` programático).

**Contexto.** O plano §7.5 é explícito: `@Transactional` não vai em controller, o serviço de publish é o único que abre
transação, e compose não abre transação. A regra do orchestrator (§4.2) é não ter Spring,
mesmo compartilhando o módulo `sdui-app`. `@Transactional` é `spring-tx`. As duas regras colidem: o caso de uso de
publish mora no orchestrator.

**Opções.** (a) `spring-tx` no orchestrator, quebrando "orchestrator sem Spring". (b) Porta pura no orchestrator,
implementação anotada em adapters, exigindo exceção na regra ArchUnit que proíbe `@Transactional` em adapters.

**Decisão.** Opção (b).

```kotlin
// sdui-app :: br.com.empresa.sdui.orchestrator.port.outbound
interface TransactionalUnitOfWork {
    fun <T> execute(work: () -> T): T
}
```

Implementação única em `..adapters.mongo.tx.MongoTransactionalUnitOfWork`, anotada com `@Transactional`.
`PublishSpecUseCase` e `RollbackPointerUseCase` recebem a porta e envolvem a coordenação de `pointers` +
`publish_requests` + `audit_log` + `idempotency`.

**Justificativa da escolha.** Manter o orchestrator livre de Spring vale mais do que a pureza da regra ArchUnit. Uma
exceção **com nome próprio de classe** é auditável e aparece no grep; `spring-tx` no orchestrator abre um precedente que
ninguém fecha depois — a próxima anotação entra sem discussão.

**Consequência.** A regra do §7.3 foi reescrita: a exceção é por nome de classe, não por pacote, e duas regras novas
fecham o contorno (nada de `org.springframework.transaction` em core/orchestrator; nada implementa a porta fora de
`..adapters.mongo.tx..`).

**Pendência operacional.** O plano §7.5 avisa que em DocumentDB é preciso validar suporte a transação multi-documento;
sem ele, o caminho é compare-and-set em `pointers.version` + outbox para o audit. A porta absorve as duas implementações
sem mudar o caso de uso, que é o principal ganho desse desenho.

---

**Nota Kotlin.** Classes Kotlin são `final` por padrão. `MongoTransactionalUnitOfWork` funciona com `@Transactional`
porque o plugin `kotlin("plugin.spring")` abre classes anotadas com `@Transactional` para o proxy. Uma alternativa sem
proxy e sem a exceção nominal está registrada no ADR-013.

---

### ADR-004 — Vocabulário: `Screen` formalizado, `Fragment` adiado

**Status:** `ACEITO`

**Contexto.** O §2 do plano fecha treze termos: Surface, Skeleton, Slot, Component type, Component instance, Spec,
Overlay, Árvore hidratada, Schema version, Capability, Targeting, Pointer, Channel. Nem "Fragment" nem "Screen" estão
lá. Esta pré-arquitetura usa `Screen`, `HydratedScreen`, `ScreenController`, `ScreenSpec`, `Fragment`, `FragmentStore`,
`FragmentResolver` e um endpoint `GET /v1/fragments/{fragmentId}`, enquanto a chave Redis do mesmo objeto é `sdui:tree:`
e o endpoint público é `/v1/surfaces/{surface}`. Três nomes para a mesma coisa.

**Decisão sobre `Screen`.** Formalizar. `Screen` é conceito canônico de SDUI (a tríade Sections / Screens / Actions) e o
código já está inteiro nele. Adicionar ao §2 do plano: **Screen = árvore hidratada de uma surface, o objeto que o
compose devolve**. `Árvore hidratada` vira sinônimo textual; em código, cache e métrica usa-se `Screen`/`tree` de forma
consistente.

**Decisão sobre `Fragment`.** **Não entra no MVP.** Nenhuma fonte do projeto define "Fragment"; o próprio §14 item 5 já
dizia para não expor fragments publicamente. Ficam fora do primeiro PR: `Fragment`, `FragmentStore`,
`FragmentResolver`, `FragmentController` e `GET /v1/fragments/{fragmentId}`. O §5 deste documento passa a ser leitura de
proposta, não especificação.

**Hipótese a validar antes de reabrir.** "Fragmento estático" parece ser sinônimo operacional de *conjunto de placements
publicado e reutilizável, sem hidratação dinâmica*. Se for isso, é uma feature de composição de spec e cabe dentro do
`SpecResolver`, sem entidade nova, sem store, sem endpoint. Só vale formalizar quando existir uma surface real além da
Home reusando o mesmo bloco.

**Consequência.** A chave Redis `sdui:fragment:static:{...}` do §4.4 fica sem consumidor no MVP. Remover do primeiro PR.

---

---

### ADR-005 — Jackson 3 com módulo Kotlin

**Status:** `ACEITO` (revisado em 18/09/2026 para Kotlin/Gradle).

**Contexto.** No Boot 4, Jackson 3 é a biblioteca padrão e o suporte a Jackson 2 está depreciado, existindo apenas para
facilitar migração. O Jackson 3 mudou groupId e pacote de `com.fasterxml.jackson` para `tools.jackson`, com exceção de
`jackson-annotations`, que mantém o groupId antigo.

**Problema original.** Declarar `com.fasterxml.jackson.core:jackson-databind` no contrato resolveria (o gerenciamento de
Jackson 2 continua no BOM), mas o contrato compilaria contra o `ObjectMapper` do Jackson 2 enquanto a api serializaria
com o `JsonMapper` do Jackson 3 autoconfigurado pelo Boot: dois databinds no classpath e testes validando um mapper que
não é o do runtime.

**Problema adicional em Kotlin.** Sem `jackson-module-kotlin`, o Jackson não entende construtor primário, parâmetros
com default nem nulabilidade de `data class`. A serialização parece funcionar, mas a desserialização de fixtures e de
requests administrativos falha. No Jackson 3 o módulo também mudou de groupId: `tools.jackson.module`.

**Decisão.**

- `sdui-contract`: `tools.jackson.core:jackson-databind` + `com.fasterxml.jackson.core:jackson-annotations`, sem versão.
- `sdui-app`: `tools.jackson.module:jackson-module-kotlin`, sem versão; o `JsonMapper` do Boot o registra.
- Nunca `com.fasterxml.jackson.module:jackson-module-kotlin`, `spring.jackson2` ou `spring-boot-jackson2`: são
  ferramentas de migração, não escolha de greenfield.
- Compilador com `-Xannotation-default-target=param-property`, para annotations Jackson em parâmetros de construtor
  caírem onde o Jackson as procura.

**Consequência.** O envelope usa o `JsonMapper` imutável do Boot, com ISO-8601 por padrão. **Os testes de serialização
vivem em `sdui-app`**, não em `sdui-contract`: só ali existe o `JsonMapper` que o Boot autoconfigura, e o requisito é
testar com ele, não com um mapper construído à mão (§6.6). `@JsonTypeInfo` continua restrito a polimorfismo de
desserialização realmente necessário.

---

### ADR-006 — Resilience4j fora do MVP

**Status:** `ACEITO`

**Contexto.** A versão Maven desta pré-arquitetura importava `resilience4j-bom:2.4.0`. Dois problemas: **nenhum módulo
declarava qualquer artefato resilience4j**, então o BOM era peso morto; e o artefato `resilience4j-spring-boot4` ficou
de fora do BOM 2.4.0, obrigando a sobrescrever a versão explicitamente de qualquer forma.

**Análise do que o MVP realmente precisa.**

| Necessidade                          | Solução no MVP                                | Precisa de Resilience4j?              |
|--------------------------------------|-----------------------------------------------|---------------------------------------|
| Timeout por section                  | `Future.get(timeout)` + `cancel(true)` (§11)  | Não                                   |
| Bounded fan-out de hydrator          | `Semaphore` compartilhado por instância (§11) | Não                                   |
| Timeout de Mongo/Redis               | Configuração do driver                        | Não                                   |
| Rate limit do compose                | Token bucket distribuído no Redis             | Não (o RateLimiter dele é in-process) |
| Circuit breaker por dependência HTTP | —                                             | Sim, **quando existir client HTTP**   |

**Decisão.** Nenhuma dependência de resiliência entra no catálogo antes de existir consumidor.

**Gatilho de reintrodução.** O primeiro `SectionHydrator` com client HTTP real. Nesse momento: declarar
`resilience4j-spring-boot4` no catálogo **com versão explícita**, verificando antes se já saiu release que corrigiu o
BOM; e somar `spring-boot-starter-aspectj` — no Boot 4 o `spring-boot-starter-aop` foi renomeado.

**Alternativa a avaliar no gatilho.** O **Spring Framework 7** traz `@Retryable` e `@ConcurrencyLimit` nativos
(habilitados explicitamente na configuração). Só vale puxar Resilience4j se o ganho for circuit breaker com métricas por
dependência; retry e limite de concorrência já estão cobertos. Em Kotlin, os beans que usam essas annotations precisam
ser proxiáveis — beans anotados com `@Component`/`@Service` já são abertos pelo `plugin.spring`.

**Consequência.** O §13.1 do plano lista Resilience4j como stack de runtime. Este ADR adia a introdução até haver
consumidor; o §13.1 deve ganhar a nota "a partir do primeiro hydrator HTTP".

---

### ADR-007 — `503` + `Retry-After` como último degrau da escada

**Status:** `ACEITO`

**Contexto.** O plano §11 fecha: se Mongo/Redis da spec falhar, usa `lastgood`; se `lastgood` não existir (boot frio),
`503` com corpo estável e `retry-after`. A tabela de respostas do §12 desta pré-arquitetura listava 200, 304, 400,
401/403, 429 e 500, sem 503 e sem `Retry-After`.

**Risco que isso criava.** Com aquela tabela como fonte, um boot frio sem `lastgood` viraria `500` — exatamente o que o
próprio §12 diz que não deve acontecer — e o app mobile perderia o sinal de retry.

**Decisão.** Escada completa fixada no §12, do melhor para o pior caso: `200` composto, `200` com `envelope.omitted`,
`200` `lastgood` com `fallback=true` + `fallbackReason`, e `503` + `Retry-After`. O `500` fica reservado a erro não
tratado fora da escada. `404` nunca, para `home`.

**Consequência.** Teste obrigatório: Redis vazio e sem `lastgood` retorna `503` com `Retry-After` e corpo estável, não
`500` nem stacktrace. Entra na matriz do §6 (§6.5 e §6.8) e nos critérios de H07/H11. O degrau seguinte é o skeleton
local do binário,
que é contrato com mobile e não código deste MS.

---

---

### ADR-008 — Maker-checker com um único engenheiro

**Status:** `PENDENTE` — decisão de processo, não de código. Registrado porque bloqueia a H09.

**Contexto.** O plano §4.3 e a H09 exigem que o maker não aprove o próprio publish, e que rollback de `stable` passe por
checker. Com **um engenheiro de backend**, não existe um segundo ator técnico para aprovar.

**O que não muda.** A regra "maker ≠ checker" **não deve ser desligada no código**. É requisito de persona regulada, e
desligá-la para caber no time de hoje significa reescrevê-la quando o time crescer, provavelmente sob pressão de
incidente.

**Opções para o checker, em ordem de preferência.**

1. **Checker é uma pessoa de produto ou de negócio**, não de backend. Aprovar uma revisão de Home é decisão de produto:
   o
   diff N-1 → N mostra copy, ordem de slots e ações. Não exige ler código. É a opção que preserva a regra e melhora a
   governança.
2. **Channel `internal` com atalho de publish** para o ciclo de desenvolvimento, mantendo maker-checker obrigatório em
   `stable` e `canary`. O plano §12 já prevê exatamente isso: "channel `internal` pode ter atalho de publish para dev,
   nunca para `stable`".
3. Conta de serviço aprovando automaticamente. **Descartada**: transforma a trilha de auditoria em ficção e é o tipo de
   coisa que compliance encontra depois.

**Decisão recomendada.** Opção 2 para o dia a dia de desenvolvimento, mais opção 1 assim que houver um par de produto
nomeado. A H09 continua implementando a regra completa; o que muda é quem é o checker, não se existe checker.

**Consequência.** O `audit_log` precisa registrar papel (`maker`/`checker`/`auditor`) mesmo quando maker e checker forem
a mesma pessoa em `internal`, com o channel explícito no registro. Assim a auditoria mostra "aprovado em internal sem
segundo ator" em vez de esconder o fato.

---

---

### ADR-009 — Section incompatível: omissão pura, com slot portante declarado

**Status:** `ACEITO`

**Contexto.** A política vigente (plano §5.4, fluxos §3, H04, H07) é: section cujo
`type@typeVersion` não pertence às capabilities efetivas é removida da árvore e
registrada em `envelope.omitted`, sem `4xx`. Simples, testável e já coberta por
H12.

O artigo de Joud descreve a alternativa do Shopify: o fallback é campo de
primeira classe no envelope de cada section, e o ganho é estrutural, não de UX —
com o fallback no envelope, torna-se impossível publicar um tipo novo sem
caminho de degradação, porque o schema não permite. Se o fallback mora no
cliente, alguém esquece de cadastrar o novo tipo e o usuário recebe espaço em
branco.

O Ghost chega ao mesmo lugar por outro caminho: cada section trafega dentro de
um `SectionContainer` que carrega, além do modelo de dados, o **status** daquela
section e os dados de logging. Ou seja, a condição da section é campo de
primeira classe do envelope, não algo que o cliente infere da ausência. Somado
ao fallback por section do Shopify, são duas implementações independentes que
trataram degradação como estrutura em vez de omissão.

Isso não inverte a decisão recomendada — omissão pura continua sendo a política
certa para section opcional, e `envelope.omitted` já responde "por que você não
recebeu isto". Mas reforça o ponto do slot portante: as duas referências
consideram inaceitável que a única informação sobre uma section ausente seja o
silêncio.

**O risco que a política atual cria.** Omissão pura é a resposta certa para uma
section opcional: `offers` some e a Home continua coerente. Não é a resposta
certa para um slot portante. Se `account_card@2` for publicado para uma faixa
onde parte da frota só declara `@1`, esses binários recebem uma Home sem o bloco
principal — tecnicamente `200`, com `omitted` preenchido, dashboard verde, e uma
tela que não cumpre a função da superfície. O modo de falha é silencioso por
construção: o MS fez exatamente o que foi mandado fazer.

**Opções.**

1. **Omissão pura, como hoje.** Custo zero. Mantém o buraco do slot portante.
2. **Omissão pura + slot portante declarado.** O documento de skeleton
   persistido no Mongo ganha `required: true|false` por slot, ao lado de
   `allowedTypes` e do máximo de instâncias. A validação de publish recusa
   spec em que um slot `required` possa ficar vazio para qualquer combinação de
   `platform × faixa de appVersion × capabilities` alcançada pelo targeting
   daquela revisão. O campo é de validação, não vai para o payload de fio.
3. **`downgradeTo` por type no catálogo.** Cada `type@N` declara o alvo de
   degradação (`credit_offer@2 → credit_offer@1`) e o compose substitui em vez
   de omitir. Resolve o caso geral e introduz um grafo de degradação que precisa
   ser validado, versionado, testado por aresta e mantido vivo — a complexidade
   que matou o HubFramework do Spotify, adotada antes de existir o problema que
   ela resolve. Hoje o catálogo tem sete types, todos `@1`: o grafo teria zero
   arestas.

**Decisão.** Opção 2. Fecha o modo de falha silencioso com um campo
booleano e uma regra de validação, sem criar entidade nova, sem tocar no
contrato de fio e sem mudar a escada de fallback. `header` e `accounts` são
`required: true`; os demais slots (`shortcuts`, `cards`, `offers`, `coverage`,
`foryou`), `false` — a lista de slots portantes nasce fixada para a Home MVP.

A opção 3 fica **fora do MVP**, com gatilho de reentrada nomeado, no mesmo
formato do ADR-004: reabrir quando o primeiro type chegar a `@2` **e** a frota
estiver dividida o bastante para que a faixa `@1` não possa ser simplesmente
coberta por uma spec própria. Enquanto uma spec por faixa resolver, spec por
faixa é mais barata que grafo de degradação.

**Consequência.**

- H03 ganha `required` no skeleton persistido e um critério de aceite para ele.
- H08 ganha a regra de recusa na validação de rascunho.
- H12 ganha teste: spec que deixa slot `required` vazio para uma faixa dentro do
  próprio targeting é recusada no publish, não em runtime, além de teste que
  prova ausência de `required` no payload de fio.
- `envelope.omitted` continua como está. O contrato de fio não muda.

---

---

### ADR-010 — `SectionComponentType` não entra no contrato

**Status:** `ACEITO` — fecha uma porta que o artigo do Ghost abre. Promovido de `PROPOSTO` porque H08 e H12 já
dependem dele.

**Contexto.** O Ghost separa *qual dado* de *como renderizar* em dois campos: o
modelo de dados da section e um `SectionComponentType` que escolhe a renderização
entre várias possíveis para o mesmo modelo. O exemplo do artigo é um título
renderizado com ou sem o tratamento visual da linha Plus. O ganho declarado é
reúso de schema: um modelo, várias caras.

Este MS tem hoje dois eixos para a mesma pergunta: `type` (qual renderer) e
`typeVersion` (qual contrato daquele renderer). Não existe um terceiro campo
escolhendo variação de renderização.

**Por que a tentação é real.** No dia em que produto pedir "o mesmo
`account_card`, mas com o tratamento de cliente premium", existem três saídas: um
type novo (`account_card_premium`, e o catálogo começa a crescer por aparência),
um campo em `props` (e aí é token visual no JSON, o antipadrão que o projeto
nomeia), ou um `componentType` como o do Ghost. A terceira parece a mais limpa e
é a que o artigo endossa.

**Por que ela não entra.** No exemplo do próprio artigo, o que o
`SectionComponentType` seleciona é logo e estilo de título — decisão de aparência
tomada no servidor. É a linha que o projeto fecha em §2 e que a H12 já faz o
build reprovar. Adotá-lo seria mover a violação de `props` para um campo de
primeira classe, com o agravante de ficar mais difícil de detectar: o linter
procura `color`, `gap`, `radius`; não procura um enum opaco cujo significado mora
no binário.

Há também uma assimetria de contexto que o artigo não discute porque não se
aplica a ele. O Ghost roda em web, iOS e Android com um framework próprio em cada
plataforma e uma equipe de plataforma dedicada mantendo o mapa
`componentType → renderer`. Aqui, esse mapa seria conhecimento tácito espalhado
entre dois binários, sem dono nomeado.

**Decisão.** Não adotar. As três situações que motivariam `componentType` têm
resposta própria:

1. **Semântica diferente, dado parecido** — é type diferente. `card_product` e
   `credit_offer` já ilustram isso no catálogo MVP: dados parecidos, conceitos de
   produto distintos, types separados. É a regra do Lyft, que o artigo do Joud
   defende: nomear pelo conceito de produto, não pela forma.
2. **Mesma semântica, ênfase diferente** — é estado semântico em `props`
   (`PREMIUM`, `REQUIRES_LOGIN`, `LOW_STOCK`), e o binário decide o que fazer com
   ele. O servidor diz o que a coisa **é**; nunca como ela aparece.
3. **Mesma semântica, mesmo estado, look diferente** — é Design System. O
   servidor não participa.

**Consequência.**

- H08 recusa, na validação, qualquer campo de section que selecione variação de
  renderização, sob qualquer nome (`componentType`, `variant`, `style`,
  `appearance`, `presentation`).
- A regra de três da H03 (D6.1) continua sendo a defesa contra o outro extremo:
  se três sections diferentes acabarem precisando do mesmo estado semântico novo,
  aí sim generaliza.
- Se o time decidir o contrário no futuro, o gatilho de reabertura precisa ser um
  caso concreto em que as três saídas acima falharam — não a leitura do artigo.

---

---

### ADR-011 — Conjunto de actions fechado, sem extensão por feature

**Status:** `ACEITO` — registra divergência deliberada do Ghost. Promovido de `PROPOSTO` porque a H12 já depende
dele.

**Contexto.** O Ghost permite que features adicionem tipos de `IAction` próprios,
roteados para handlers de escopo da feature, e o artigo apresenta isso como
liberdade: a feature põe quanta lógica de negócio precisar no seu handler. Este
MS fecha o conjunto em `navigate`, `open_bottom_sheet`, `track` e `noop`, e a
H12 reprova o build se aparecer um quinto.

**Decisão.** Manter fechado.

O que o Ghost chama de liberdade é, no nosso desenho, a migração da regra de
negócio de volta para o binário — uma por feature, sem registro central e sem
versionamento. Cada action nova é um contrato implícito entre um time de produto
e dois binários, que ninguém consegue auditar e cuja remoção exige saber quais
versões de app ainda a implementam. É o oposto do motivo pelo qual este MS
existe.

A assimetria de contexto vale de novo: o Ghost tem framework próprio por
plataforma e equipe de plataforma mantendo o roteamento. Aqui, action nova é
combinação verbal entre backend e mobile.

**Consequência.** Action nova é mudança de contrato: entra pelo mesmo processo de
`typeVersion` e matriz de capabilities, com faixa de app declarada, e não por
acordo entre dois times. O custo alto é intencional — é o que impede o conjunto
de virar dezoito.

---

---

### ADR-012 — Sem coroutines no MVP

**Status:** `ACEITO` (18/09/2026). Registra o que o desenho já pressupõe, para que a escolha de Kotlin não o reabra
por acidente.

**Contexto.** O serviço é Spring MVC, com virtual threads (`spring.threads.virtual.enabled`) e drivers bloqueantes de
MongoDB e Redis. Kotlin oferece coroutines, e a tentação natural é escrever `suspend fun hydrate(...)`. Misturar os dois
modelos obriga a escolher entre repositórios reativos (outro driver, outro modelo de transação, outro modelo de teste)
ou pontes `runBlocking` no caminho quente — que bloqueiam do mesmo jeito e ainda escondem o custo.

**Decisão.** Nenhuma `suspend fun`, nenhum `kotlinx.coroutines`, nenhum repositório reativo em produção. Concorrência de
I/O é feita com virtual threads e `Semaphore`, conforme §11. A SPI `SectionHydrator` é bloqueante.

**Consequência.** Regra ArchUnit: nada de `kotlinx.coroutines..` em classes de produção (§7.3). Reabrir somente com um
caso concreto que virtual threads não resolvam — por exemplo, streaming de resposta —, e então com ADR que troque o
modelo inteiro, não uma parte dele.

---

### ADR-013 — `TransactionalUnitOfWork` com `TransactionTemplate`

**Status:** `ACEITO` — supersedendo o mecanismo de anotação do ADR-003. Validação arquitetural sem exceções nominais.

**Contexto.** O ADR-003 implementa a porta com uma classe anotada com `@Transactional`, o que exige proxy AOP, classe
aberta (em Kotlin, via `plugin.spring`) e uma exceção nominal na regra ArchUnit que proíbe `@Transactional` em adapters.

**Proposta.** Implementar a porta programaticamente:

```kotlin
class MongoTransactionalUnitOfWork(
    private val transactionTemplate: TransactionTemplate,   // construído sobre o MongoTransactionManager
) : TransactionalUnitOfWork {
    override fun <T> execute(work: () -> T): T =
        transactionTemplate.execute { work() }
            ?: error("TransactionTemplate retornou null para um trabalho não-nulo")
}
```

**Ganho.** Sem proxy e sem dependência do `allopen` para esta classe; a regra ArchUnit passa a ser "nenhum
`@Transactional` no projeto", **sem exceção**; o limite da transação fica visível no código em vez de numa annotation.
A porta, os casos de uso e a pendência de DocumentDB do ADR-003 não mudam.

**Custo.** O `?: error(...)` existe porque `TransactionTemplate.execute` é anotado como nullable; um `work` que
legitimamente devolva `null` precisaria de uma variante `executeNullable`. Nada no MVP precisa disso.

**Para aceitar.** Promover este ADR a `ACEITO`, marcar o ADR-003 como "mecanismo supersedido pelo ADR-013 (a porta
permanece)" e simplificar a regra do §7.3.

---

## Conclusão

A proposta de testes por camada e de preparar o serviço para telas dinâmicas e múltiplas squads **faz sentido**, desde
que o desenho mantenha limites simples e ownership claro.

A pré-arquitetura fica assim:

```text
sdui-contract          -> contrato REST/JSON público
sdui-core              -> regras SDUI puras (Kotlin sem Spring)
sdui-app               -> orchestrator (casos de uso, portas, SPI de hydrators)
                          + adapters (Mongo, Redis, HTTP quando houver)
                          + api (controllers MVC e adaptação HTTP)
sdui-bootstrap         -> composição Spring Boot e YAML
sdui-integration-test  -> integração real e ArchUnit
```

A decisão mais importante é não transformar a modularização em plataforma genérica antes da necessidade: quatro módulos
de produção, interfaces pequenas, composição explícita e testes que protegem regras reais. A divisão de `sdui-app` ou a
extração de uma SPI distribuída entre squads acontece quando ownership, deploy ou ciclo de vida justificarem.

---

## Referências

- [Spring Boot 4.1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot — Testing Spring Boot Applications](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- [Spring Boot — Kotlin Support](https://docs.spring.io/spring-boot/reference/features/kotlin.html)
- [Spring Framework 7.0 — REST Clients](https://docs.spring.io/spring-framework/reference/7.0/integration/rest-clients.html)
- [Gradle — Sharing build logic with convention plugins](https://docs.gradle.org/current/userguide/sharing_build_logic_between_subprojects.html)
- [Gradle — Version catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html)
- [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)
- [JUnit User Guide](https://docs.junit.org/current/user-guide/)
- [MockK](https://mockk.io/)
- Plano de construção — ms-sdui-composer: `plano-servico-sdui.md`
