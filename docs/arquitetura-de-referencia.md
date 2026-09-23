# Arquitetura Canônica de Referência — ms-sdui-composer

**ms-sdui-composer** = o serviço que, a cada request, compõe a árvore de UI da surface a partir de uma spec versionada,
do contexto do cliente e das capabilities, devolvendo um envelope pronto e seguro para o app.

## Kotlin, Gradle, Clean Architecture pragmática e estratégia de testes

**Baseline:** Kotlin 2.4.20 sobre JVM Java 25 LTS, Spring Boot 4.1.1 (que gerencia Spring Framework 7.0.x), Gradle 9.7.1
com Kotlin DSL, multi-projeto.

**Objetivo:** estruturar o **ms-sdui-composer** para suportar composição de surfaces por múltiplos times, evolução de
telas, hidratação dinâmica por squads e testes com níveis claros de isolamento.


---

## 1. Decisão arquitetural

O serviço é um **build Gradle multi-projeto** (Kotlin DSL), com `settings.gradle.kts` na raiz, version catalog em
`gradle/libs.versions.toml` e convention plugins em `build-logic/`. O único artefato executável é `sdui-bootstrap`; os
demais produzem JARs internos.

Toda a produção é escrita em **Kotlin** (`src/main/kotlin`), e todos os testes também (`src/test/kotlin`). Não há fontes
Java, salvo exigência comprovada de integração externa.

A organização segue uma **Clean Architecture pragmática**, com fronteiras em dois níveis:

| Fronteira | Mecanismo | Por quê |

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
│   ├── ContextValidator.kt
│   ├── SpecResolver.kt
│   ├── CapabilityFilter.kt
│   ├── HydrationCoordinator.kt
│   ├── FallbackResolver.kt
│   └── ResponseAssembler.kt
├── port/
│   ├── inbound/
│   │   ├── ComposeScreenUseCase.kt      # com ComposeRequest e ComposeResult, o contrato da porta
│   │   ├── AuditQueryUseCase.kt
│   │   ├── AdminErrors.kt               # AdminDenied, AdminConflict, ... traduzidas pela borda
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

## 5. Estratégia de testes

Testes são escritos em Kotlin, com JUnit Jupiter e AssertJ na versão do BOM. **Testcontainers não entra em teste
unitário**: pertence aos testes de adapter e de integração.

### 5.1 Matriz de testes

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

### 5.2 Unitários: `sdui-core` e `orchestrator`

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

### 5.3 Adapter Mongo

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

### 5.4 Adapter Redis

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

### 5.5 API: `@WebMvcTest` + MockMvc

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

### 5.6 Serialização do contrato

Os testes de serialização validam o JSON de fio com o **mesmo `JsonMapper` que o Boot autoconfigura** (ADR-005). Por
isso eles vivem em `sdui-app` (slice de JSON do Boot, ou `@WebMvcTest` verificando o corpo), não em `sdui-contract`,
que não tem Boot. Um mapper construído à mão no teste pode passar enquanto a resposta HTTP sai diferente.

Cobertura mínima: nomes de campo, ausência de campos nulos conforme contrato, datas ISO-8601, ausência de campos
proibidos (aparência, geometria, variação de renderização) e ausência de `required` (ADR-009) no payload.

### 5.7 WireMock: somente se houver client HTTP

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

### 5.8 Integração/E2E leve

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

## 6. ArchUnit e regras arquiteturais

As regras protegem a direção das dependências e os limites do projeto, não impõem nomenclatura sem valor. Com
`orchestrator`, `adapters` e `api` no mesmo módulo, **o ArchUnit é a única proteção entre essas três camadas** — por
isso as regras abaixo são obrigatórias, não recomendações.

### 6.1 Dependência e forma de uso

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

### 6.2 Regras vigentes

A fonte de verdade é `sdui-integration-test/src/test/kotlin/br/com/empresa/sdui/it/ArchitectureTest.kt`. A tabela
resume o que cada regra protege; o código não é repetido aqui para não divergir.

| Regra                                                                                          | Forma                                           | Protege                                                                                                    |
|------------------------------------------------------------------------------------------------|-------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| `core` depende só de JDK, stdlib Kotlin e de si mesmo                                          | lista de permissão                              | Domínio puro (§3). Pega também Micrometer, SLF4J e `kotlinx`                                               |
| `orchestrator` depende só de JDK, stdlib Kotlin, `core` e de si                                | lista de permissão                              | Aplicação sem Spring, Jackson, contrato, driver ou borda                                                   |
| `contract` não depende de `core`, frameworks nem outras camadas                                | lista de proibição                              | DTOs públicos independentes do domínio                                                                     |
| `api` não depende de `adapters`                                                                | lista de proibição                              | Borda HTTP não conhece a implementação dos stores                                                          |
| `api` acessa o `orchestrator` só por `port..` e nunca por `*Store`                             | predicado                                       | Borda passa sempre pelo caso de uso e pela regra de papel que ele aplica                                   |
| `adapters` não dependem de `api` nem de `contract`                                             | lista de proibição                              | Só `api` converte entre modelos e DTOs                                                                     |
| Pacotes de produção livres de ciclos                                                           | `slices().matching("br.com.empresa.sdui.(**)")` | Direção única também entre subpacotes, não só entre camadas                                                |
| `adapters.mongo` e `adapters.redis` não dependem um do outro                                   | lista de proibição                              | Autoridade (Mongo) e cache (Redis) substituíveis isoladamente (ADR-021)                                    |
| `SectionHydrator` em `orchestrator.hydration`; implementações em `hydration` ou `adapters`     | nome e `implement`                              | SPI de hidratação no lugar certo                                                                           |
| Controllers, advices e classes `*Controller` só em `api`                                       | anotação e nome                                 | HTTP confinado à borda                                                                                     |
| `@SpringBootApplication` só em `bootstrap`                                                     | anotação                                        | Um único ponto de entrada executável                                                                       |
| Nenhuma dependência de `org.springframework.transaction..` ou `jakarta.transaction..`          | lista de proibição                              | Transação só pela porta `TransactionalUnitOfWork` (ADR-013); pega `@Transactional` e `TransactionTemplate` |
| Nenhuma dependência de `kotlinx.coroutines..` ou `kotlin.coroutines..`                         | lista de proibição                              | Sem coroutines nem `suspend fun` (ADR-012)                                                                 |
| Nenhuma dependência de Reactor, WebFlux ou RxJava                                              | lista de proibição                              | Stack bloqueante com virtual threads (ADR-012)                                                             |
| Drivers (`com.mongodb`, `org.bson`, `io.lettuce`, `org.springframework.data`) só em `adapters` | lista de proibição                              | Persistência confinada aos adapters                                                                        |

Regras com `.that()` que não encontram classes falham por padrão (`failOnEmptyShould`); não usar
`.allowEmptyShould(true)`. Se uma classe citada por nome for movida ou renomeada, a regra falha e precisa ser
atualizada junto.

### 6.3 Critérios de desenho das regras

- **Pureza é lista de permissão.** Lista de proibição só barra o que alguém lembrou de listar. Para `core` e
  `orchestrator` a permissão libera `java..`, `kotlin..` e `org.jetbrains.annotations..`, que o bytecode Kotlin
  referencia por conta própria. Classes sintéticas (`*Kt`, `$Companion`, `$WhenMappings`) residem no pacote do fonte e
  são cobertas normalmente.
- **`suspend fun` não depende de `kotlinx`.** Ela compila com um parâmetro `kotlin.coroutines.Continuation`, da stdlib;
  por isso a regra barra `kotlin.coroutines..` além de `kotlinx.coroutines..`. Builders `sequence {}` e `iterator {}`
  também referenciam esse pacote e ficam proibidos junto.
- **Tipos de contrato ficam na porta.** `ComposeRequest`, `ComposeResult`, os comandos e as exceções `Admin*` vivem em
  `orchestrator.port.inbound`; `HydrationContext`, `HydrationResult` e `SectionHydrator` em `orchestrator.hydration`.
  Tipo usado pela borda dentro do pacote da implementação cria ciclo `port ↔ implementação` e obriga a borda a
  importar a implementação.
- **Leitura administrativa também é caso de uso.** A trilha de auditoria sai por `AuditQueryUseCase`, que aplica o
  papel (checker ou auditor); o controller não injeta `AuditLogStore`. `MetricsRecorder`, `MetricNames` e as
  exceções `StoreConflict`/`StoreRejected` de `port.outbound` continuam permitidos na borda.
- **Uma importação por execução.** `ClassFileImporter` varre o classpath; o resultado fica em `companion object`, porque
  o JUnit cria uma instância da classe por método de teste.
- **Sem regra redundante.** Uma regra coberta inteiramente por outra só dá a falsa impressão de proteção extra; o nome
  de cada teste descreve exatamente o que ele verifica.
- `adapters.configuration` é a raiz de composição: monta os serviços concretos do `orchestrator` e por isso depende
  deles. Nenhum outro pacote de `adapters` deve instanciar serviço do `orchestrator`.

### 6.4 ArchUnit e fronteiras Gradle

ArchUnit verifica dependências no bytecode, mas não substitui a separação de módulos. Os dois níveis são usados:

- **Gradle** impede que uma dependência exista no classpath: `sdui-core` e `sdui-contract` não enxergam Spring, e a task
  `verifyPureClasspath` falha o build se isso mudar. `verifyForbiddenDependencies` impede gRPC, Protobuf, GraphQL,
  MapStruct e Kafka em qualquer módulo.
- **ArchUnit** impede violações entre pacotes dentro do grafo permitido, principalmente dentro de `sdui-app`.

Se uma fronteira puder ser garantida pelo Gradle, ela não deve depender só do ArchUnit.

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
